package br.unb.cic.td20.verification;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.joint.ErikssonRootGenerator;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.joint.ErikssonInverseReconstruction;
import br.unb.cic.td20.joint.ErikssonInventory;
import br.unb.cic.td20.joint.SupportClearing;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.runtime.ProjectPaths;
import br.unb.cic.td20.runtime.CheckerParallelism;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/** CPU-only regeneration of all requested obligations, followed by witness replay. */
public final class ErikssonCertificateChecker {
    private ErikssonCertificateChecker() { }
    public static void main(final String[] arguments) throws IOException {
        final var options = Options.parse(arguments,ProjectPaths.root(Path.of("")));
        if (options.help) { usage(); return; }
        final var inventory = ErikssonInventory.read(options.paper);
        final var endpoints = ErikssonInventory.endpoints();
        System.out.printf("joint-selection inventory checked: rootPartitions=%d endpointAnnotations=%d elementaryEndpoints=%d%n",
                inventory.size(),endpoints.size(),endpoints.stream().filter(ErikssonInventory.Endpoint::elementaryClosure).count());
        System.out.printf("checker parallelism: workers=%d braceletWorkers=%d lookupBatch=%d lookupPipelines=%d reconstructionBufferKeys=%d%n",
                CheckerParallelism.workers(), DirectTwistedBraceletGenerator.configuredWorkerCount(),
                CheckerParallelism.lookupBatchSize(), CheckerParallelism.lookupPipelines(),
                CheckerParallelism.reconstructionBufferKeys());
        if (options.structuralOnly) {
            System.out.println("Structural inventory checks only; sorting certificates and final avoidance remain unchecked.");
            return;
        }
        try (final var progress = new VerificationProgress(); final var certificates = new CertificateStore(options.database);
             final var residuals = new CheckerStateSet(options.work.resolve("endpoint-residuals"))) {
            if (options.roots()) for (final var row : inventory)
                if (options.includesPartition(row.number())) verifyRoot(row,certificates,progress);
            if (options.endpoints() || options.avoidance()) for (final var endpoint : endpoints)
                if (options.includesEndpoint(endpoint.number())) verifyEndpoint(endpoint,certificates,residuals,progress);
            final long residualCount = residuals.seal().count();
            if (options.avoidance()) verifyAvoidance(certificates,residuals,options.work,progress);
            else if (residualCount > 0) System.out.printf("Endpoint failures requiring final avoidance: %d%n",residualCount);
            if (options.completeScope()) System.out.println("All regenerated obligations in the complete joint-selection scope are verified.");
            else System.out.println("Requested partial verification scope completed; this is not a complete TD(20) certificate check.");
        } finally { DirectTwistedBraceletGenerator.shutdownExecutor(); }
    }
    static void verifyRoot(final ErikssonInventory.Root row,final CertificateStore certificates,final VerificationProgress progress) {
        row.requireCompleteCounts();
        progress.phase("independent Eriksson root partition "+row.number());
        try (var verifier=required(certificates,ErikssonRoute.ROOT_BUDGET)) {
            var counts=ErikssonRootGenerator.generate(row.partition(),CheckerParallelism.workers(),verifier::check,pair->{
                if (!SupportClearing.closes(pair)) verifier.accept(pair);
            });
            if (counts.unoriented()!=row.unoriented() || counts.oriented()!=row.oriented() || counts.supportCleared()!=row.supportCleared())
                throw new IllegalStateException("Independent active root counts disagree at partition "+row.number());
        } catch (RuntimeException|Error error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Independent root regeneration failed",error); }
    }
    static void verifyEndpoint(final ErikssonInventory.Endpoint endpoint, final CertificateStore certificates,
                               final CheckerStateSet residuals, final VerificationProgress progress) {
        if (endpoint.elementaryClosure()) return;
        progress.phase("independent Burnside count for endpoint " + endpoint.number());
        final long expected = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                endpoint.partition(),endpoint.orientedByPart());
        final var generated = new LongAdder(); final var residual = new LongAdder();
        progress.phase(() -> "endpoint " + endpoint.number() + ": regenerated=" + generated.sum() + " residual=" + residual.sum());
        try (final var verifier = new CertificateBatchVerifier(certificates, ErikssonRoute.ENDPOINT_BUDGET, pair -> {
            residuals.add(new SearchState(ErikssonRoute.ENDPOINT_BUDGET, pair)); residual.increment();
        })) {
            DirectTwistedBraceletGenerator.generateRepresentativesConcurrently(endpoint.partition(),endpoint.orientedByPart(),
                    verifier::check, pair -> { generated.increment(); verifier.accept(pair); });
        }
        if (generated.sum() != expected) throw new IllegalStateException(
                "Endpoint generation count disagrees with Burnside: endpoint=" + endpoint.number()
                        + " generated=" + generated.sum() + " expected=" + expected);
        System.out.printf("endpoint %d checked: generated=%d residuals=%d%n",endpoint.number(),generated.sum(),residual.sum());
    }
    /** Missing witnesses become regenerated obligations even without a stored marker. */
    static boolean checkEndpointPair(final CyclicTargetPair pair, final CertificateStore certificates,
                                     final CheckerStateSet residuals) {
        if (certificates.find(pair,ErikssonRoute.ENDPOINT_BUDGET).isPresent()) return false;
        residuals.add(new SearchState(ErikssonRoute.ENDPOINT_BUDGET,pair));
        return true;
    }
    static void verifyAvoidance(final CertificateStore certificates,final CheckerStateSet endpoints,final Path work,final VerificationProgress progress) {
        int workers=CheckerParallelism.workers(),budget=ErikssonRoute.ROOT_BUDGET;
        try (var afterTwo=new CheckerStateSet(work.resolve("after-two"));
             var afterOne=new CheckerStateSet(work.resolve("after-one"));
             var roots=new CheckerStateSet(work.resolve("roots"))) {
            long endpointCount=endpoints.seal().count();
            progress.phase("regenerating third-move inverse supports");
            endpoints.forEachConcurrently(workers,state->{
                requireStage(state,0);
                ErikssonInverseReconstruction.preimages(state.pair(),2,parent->afterTwo.add(new SearchState(budget-2,parent)));
            });
            long twoCount=afterTwo.seal().count();
            progress.phase("checking after-two states and both second-move inverse branches");
            afterTwo.forEachConcurrently(workers,batch->{
                batch.forEach(state->requireStage(state,1));
                return certificates.findAll(batch.stream().map(SearchState::pair).toList(),budget-2);
            },state->{},state->{
                for (int gain:new int[]{2,3}) ErikssonInverseReconstruction.preimages(state.pair(),gain,
                        parent->afterOne.add(new SearchState(budget-1,parent)));
            });
            long oneCount=afterOne.seal().count();
            progress.phase("checking after-one states and reversing gains zero and one");
            afterOne.forEachConcurrently(workers,batch->{
                batch.forEach(state->requireStage(state,2));
                return certificates.findAll(batch.stream().map(SearchState::pair).toList(),budget-1);
            },state->{},state->{
                int gain=state.pair().getBeta().size()==ErikssonRoute.ROOT_SYMBOLS-1 ? 1:0;
                ErikssonInverseReconstruction.preimages(state.pair(),gain,parent->roots.add(new SearchState(budget,parent)));
            });
            long rootCount=roots.seal().count();
            progress.phase("verifying reconstructed roots");
            roots.forEachConcurrently(workers,batch->{
                batch.forEach(state->requireStage(state,3));
                return certificates.findAll(batch.stream().map(SearchState::pair).toList(),budget);
            },state->{},state->{throw missing(state.pair(),budget);});
            System.out.printf("Eriksson inverse coverage verified: endpoints=%d afterTwo=%d afterOne=%d roots=%d%n",endpointCount,twoCount,oneCount,rootCount);
        }
    }
    private static void requireStage(SearchState state,int stage) {
        int n=state.pair().getBeta().size(),budget=ErikssonRoute.ROOT_BUDGET;
        boolean valid=switch(stage) {
            case 0 -> n==ErikssonRoute.ENDPOINT_SYMBOLS && state.maximumMoves()==budget-3;
            case 1 -> n==ErikssonRoute.ROOT_SYMBOLS-3 && state.maximumMoves()==budget-2;
            case 2 -> (n==ErikssonRoute.ROOT_SYMBOLS-1 || n==ErikssonRoute.ROOT_SYMBOLS) && state.maximumMoves()==budget-1;
            case 3 -> n==ErikssonRoute.ROOT_SYMBOLS && state.maximumMoves()==budget;
            default -> false;
        };
        if (!valid) throw new IllegalStateException("Wrong independent inverse domain or budget at stage "+stage);
    }
    private static CertificateBatchVerifier required(final CertificateStore certificates, final int maximumMoves) {
        return new CertificateBatchVerifier(certificates, maximumMoves, pair -> { throw missing(pair, maximumMoves); });
    }
    private static IllegalStateException missing(final CyclicTargetPair pair, final int maximumMoves) {
        return new IllegalStateException("Missing sorting certificate: classKey="
                + pair.extendedToricClassKey() + ", maximumMoves=" + maximumMoves);
    }
    private static void usage() {
        System.out.println("CertificateChecker [--phase=roots|endpoints|avoidance|all] [--partitions=FIRST-LAST]"
                + " [--endpoints=FIRST-LAST] [--paper=PATH] [--database=PATH] [--work=PATH] [--structural-only]");
    }
    record Options(Path paper,Path database,Path work,String phase,int firstPartition,int lastPartition,
                   int firstEndpoint,int lastEndpoint,boolean structuralOnly,boolean help) {
        static Options parse(final String[] args, final Path root) throws IOException {
            Path paper=root.resolve("docs/td20-reduction-count.tex"), database=Path.of(System.getProperty("td20.certificateDb",root.resolve("certificates").toString()));
            Path work=root.resolve("checker/target/eriksson-verification-"+ProcessHandle.current().pid()+"-"+System.nanoTime());
            String phase="all"; int firstPartition=1,lastPartition=ErikssonRoute.ROOT_PARTITIONS,firstEndpoint=1,lastEndpoint=ErikssonRoute.ENDPOINT_TYPES;
            boolean structural=false,help=false;
            for (final var arg:args) {
                if(arg.equals("--help")) help=true;
                else if(arg.equals("--structural-only")) structural=true;
                else if(arg.startsWith("--phase=")) phase=arg.substring(8);
                else if(arg.startsWith("--paper=")) paper=Path.of(arg.substring(8));
                else if(arg.startsWith("--database=")) database=Path.of(arg.substring(11));
                else if(arg.startsWith("--work=")) work=Path.of(arg.substring(7));
                else if(arg.startsWith("--partitions=")) { final int[] r=range(arg.substring(13),ErikssonRoute.ROOT_PARTITIONS);firstPartition=r[0];lastPartition=r[1]; }
                else if(arg.startsWith("--endpoints=")) { final int[] r=range(arg.substring(12),ErikssonRoute.ENDPOINT_TYPES);firstEndpoint=r[0];lastEndpoint=r[1]; }
                else throw new IllegalArgumentException("Unknown argument: "+arg);
            }
            if(!List.of("roots","endpoints","avoidance","all").contains(phase)) throw new IllegalArgumentException("Invalid checker phase");
            paper=paper.toAbsolutePath().normalize();database=database.toAbsolutePath().normalize();work=work.toAbsolutePath().normalize();
            if(!help&&!structural) {
                final var db=database.toRealPath(); Files.createDirectories(work.getParent());
                final var actualWork=work.getParent().toRealPath().resolve(work.getFileName());
                if(actualWork.startsWith(db)||db.startsWith(actualWork)||Files.exists(work))
                    throw new IllegalArgumentException("Checker work must be fresh and outside the certificate database");
                Files.createDirectory(work);
            }
            return new Options(paper,database,work,phase,firstPartition,lastPartition,firstEndpoint,lastEndpoint,structural,help);
        }
        private static int[] range(final String value, final int maximum) {
            final var parts=value.split("-",-1);
            if(parts.length!=2) throw new IllegalArgumentException("Expected FIRST-LAST range");
            final int first=Integer.parseInt(parts[0]),last=Integer.parseInt(parts[1]);
            if(first<1||last<first||last>maximum) throw new IllegalArgumentException("Invalid range");
            return new int[]{first,last};
        }
        boolean roots(){return phase.equals("all")||phase.equals("roots");}
        boolean endpoints(){return phase.equals("all")||phase.equals("endpoints");}
        boolean avoidance(){return phase.equals("all")||phase.equals("avoidance");}
        boolean includesPartition(final int n){return firstPartition<=n&&n<=lastPartition;}
        boolean includesEndpoint(final int n){return firstEndpoint<=n&&n<=lastEndpoint;}
        boolean completeScope(){return phase.equals("all")&&firstPartition==1&&lastPartition==ErikssonRoute.ROOT_PARTITIONS&&firstEndpoint==1&&lastEndpoint==ErikssonRoute.ENDPOINT_TYPES;}
    }
}
