package br.unb.cic.td20.verification;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.joint.ErikssonRootGenerator;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.joint.FinalInverseCover;
import br.unb.cic.td20.joint.ErikssonInventory;
import br.unb.cic.td20.joint.SupportClearing;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.joint.PrimitiveInverseReconstruction;
import br.unb.cic.td20.runtime.ProjectPaths;
import br.unb.cic.td20.runtime.CheckerParallelism;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
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
        System.out.printf("checker parallelism: workers=%d braceletWorkers=%d readWorkers=%d lookupBatch=%d producerBatch=%d bufferedMiB=%d readCacheMiB=%d reconstructionBufferKeys=%d%n",
                CheckerParallelism.workers(), DirectTwistedBraceletGenerator.configuredWorkerCount(),
                CheckerParallelism.readWorkers(), CheckerParallelism.lookupBatchSize(),
                CheckerParallelism.producerBatchSize(), CheckerParallelism.bufferedBytes() / (1L << 20),
                CheckerParallelism.readCacheBytes() / (1L << 20),
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
        final String type = "root type " + row.number() + "/83 partition=" + Arrays.toString(row.partition());
        final var generation = new ErikssonRootGenerator.Progress();
        try (var verifier=required(certificates,ErikssonRoute.ROOT_BUDGET)) {
            progress.phase(() -> type + " prefixes=" + generation.completedPrefixes() + "/" + generation.discoveredPrefixes()
                    + (Boolean.getBoolean("td20.checkerVerboseProgress")
                        ? " " + verifier.progress() + " " + certificates.readProgress() : ""),
                    generation::prefixNodes, "nodes");
            var counts=ErikssonRootGenerator.generatePrimitive(row.partition(),CheckerParallelism.workers(),verifier::check,pair->{
                if (!SupportClearing.closes(pair)) verifier.accept(pair);
            }, generation);
            if (counts.unoriented()!=row.unoriented() || counts.oriented()!=row.oriented() || counts.supportCleared()!=row.supportCleared())
                throw new IllegalStateException("Independent active root counts disagree at partition "+row.number());
        } catch (RuntimeException|Error error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Independent root regeneration failed",error); }
        progress.phase(type + " complete; certificateClasses=" + row.directClasses());
    }
    static void verifyEndpoint(final ErikssonInventory.Endpoint endpoint, final CertificateStore certificates,
                               final CheckerStateSet residuals, final VerificationProgress progress) {
        final String type = "endpoint type " + endpoint.number() + "/143 partition=" + Arrays.toString(endpoint.partition());
        if (endpoint.elementaryClosure()) { progress.phase(type + " complete; elementary closure"); return; }
        progress.phase(type + " counting total classes");
        final long expected = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                endpoint.partition(),endpoint.orientedByPart());
        final var generated = new LongAdder(); final var residual = new LongAdder();
        try (final var verifier = new CertificateBatchVerifier(certificates, ErikssonRoute.ENDPOINT_BUDGET, pair -> {
            residuals.add(new SearchState(ErikssonRoute.ENDPOINT_BUDGET, pair)); residual.increment();
        })) {
            progress.phase(() -> type + " checked=" + fraction(verifier.checkedCount(), expected)
                    + (Boolean.getBoolean("td20.checkerVerboseProgress")
                        ? " regenerated=" + generated.sum() + " " + verifier.progress() + " " + certificates.readProgress() : ""),
                    verifier::checkedCount, "classes");
            DirectTwistedBraceletGenerator.generatePrimitiveRepresentativesConcurrently(endpoint.partition(),endpoint.orientedByPart(),
                    verifier::check, pair -> { generated.increment(); verifier.accept(pair); });
        }
        if (generated.sum() != expected) throw new IllegalStateException(
                "Endpoint generation count disagrees with Burnside: endpoint=" + endpoint.number()
                        + " generated=" + generated.sum() + " expected=" + expected);
        progress.phase(type + " complete; checked=" + generated.sum() + " residuals=" + residual.sum());
    }
    private static String fraction(long count, long total) {
        if (total < 0) return count + "/unknown";
        return count + "/" + total + (total == 0 ? ""
                : String.format(Locale.ROOT, " (%.1f%%)", 100.0 * count / total));
    }
    /** Missing witnesses become regenerated obligations even without a stored marker. */
    static boolean checkEndpointPair(final CyclicTargetPair pair, final CertificateStore certificates,
                                     final CheckerStateSet residuals) {
        if (certificates.find(pair,ErikssonRoute.ENDPOINT_BUDGET).isPresent()) return false;
        residuals.add(new SearchState(ErikssonRoute.ENDPOINT_BUDGET,pair));
        return true;
    }
    record AfterTwoInventory(long closed,long residual) { }
    record InverseCoverage(long endpoints,long afterTwo,long afterTwoClosed,long afterTwoResiduals,long afterOne,long roots,
                           FinalInverseCover.Counts finalCover) {
        InverseCoverage {
            if (endpoints<0 || afterTwo<0 || afterTwoClosed<0 || afterTwoResiduals<0
                    || Math.addExact(afterTwoClosed,afterTwoResiduals)!=afterTwo
                    || finalCover.sources()!=afterOne || roots<0 || roots>finalCover.residualParents())
                throw new IllegalArgumentException("Incomplete residual-root coverage inventory");
        }
    }
    static InverseCoverage verifyAvoidance(final CertificateStore certificates,final CheckerStateSet endpoints,final Path work,final VerificationProgress progress) {
        int workers=CheckerParallelism.workers(),budget=ErikssonRoute.ROOT_BUDGET;
        try (var afterTwo=new CheckerStateSet(work.resolve("after-two"));
             var secondLevel=new CheckerStateSet(work.resolve("after-two-residuals"));
             var afterOne=new CheckerStateSet(work.resolve("after-one"));
             var roots=new CheckerStateSet(work.resolve("roots"))) {
            long endpointCount=endpoints.seal().count();
            progress.phase("regenerating third-move inverse supports");
            var inverse=ThreadLocal.withInitial(PrimitiveInverseReconstruction::new);
            endpoints.forEachConcurrently(workers,state->{
                requireStage(state,0);
                afterTwo.addGeneratedPrimitive(budget-2,output -> inverse.get().preimages(state.pair(),2,output));
            });
            long twoCount=afterTwo.seal().count();
            progress.phase("replaying budget-nine results and independently deriving second-level residuals");
            var retained=retainAfterTwoResiduals(certificates,afterTwo,secondLevel);
            if(Math.addExact(retained.closed(),retained.residual())!=twoCount)
                throw new IllegalStateException("After-two result accounting differs from the regenerated inventory");
            progress.phase("expanding second-level residuals through both second-move inverse branches");
            expandSecondMovePreimages(secondLevel,afterOne);
            long oneCount=afterOne.seal().count();
            progress.phase("covering every gain-zero/one first inverse by elementary closure or residual generation, without budget-ten lookup");
            var finalCover=expandFirstMovePreimages(afterOne,roots);
            long rootCount=roots.seal().count();
            progress.phase("verifying reconstructed roots outside elementary closures");
            verifyFinalRoots(certificates,roots);
            var coverage=new InverseCoverage(endpointCount,twoCount,retained.closed(),retained.residual(),oneCount,rootCount,finalCover);
            publishInverseCoverage(work,coverage);
            System.out.printf("Eriksson inverse coverage verified: endpoints=%d afterTwo=%d afterTwoClosed=%d afterTwoResiduals=%d afterOne=%d residualRoots=%d rootInventory=ELEMENTARY_RESIDUALS budgetTenLookup=false%n",
                    endpointCount,twoCount,retained.closed(),retained.residual(),oneCount,rootCount);
            return coverage;
        }
    }
    /** Derive every residual from actual replay; stored obstruction markers are never consulted. */
    static AfterTwoInventory retainAfterTwoResiduals(final CertificateStore certificates,final CheckerStateSet afterTwo,final CheckerStateSet residuals) {
        var closed=new LongAdder();var missing=new LongAdder();
        afterTwo.forEachConcurrently(CheckerParallelism.workers(),batch->{
            batch.forEach(state->requireStage(state,1));
            return certificates.findAllPrimitive(batch.stream().map(SearchState::pair).toList(),ErikssonRoute.ROOT_BUDGET-2);
        },state->closed.increment(),state->{residuals.add(state);missing.increment();});
        long count=residuals.seal().count();
        if(count!=missing.sum()) throw new IllegalStateException("Duplicate or missing independently regenerated second-level residual");
        return new AfterTwoInventory(closed.sum(),count);
    }
    static void expandSecondMovePreimages(final CheckerStateSet residuals,final CheckerStateSet afterOne) {
        var inverse=ThreadLocal.withInitial(PrimitiveInverseReconstruction::new);
        residuals.forEachConcurrently(CheckerParallelism.workers(),state->{
            requireStage(state,1);
            for(int gain=2;gain<=3;gain++) {
                final int restored=gain;
                afterOne.addGeneratedPrimitive(ErikssonRoute.ROOT_BUDGET-1,
                        output -> inverse.get().preimages(state.pair(),restored,output));
            }
        });
    }
    /** Budget-ten states are generation obligations; none is suppressed by a certificate lookup. */
    static FinalInverseCover.Counts expandFirstMovePreimages(final CheckerStateSet afterOne,final CheckerStateSet roots) {
        long expectedSources=afterOne.seal().count();
        var instances=new java.util.concurrent.ConcurrentLinkedQueue<FinalInverseCover>();
        var local=ThreadLocal.withInitial(()->{var cover=new FinalInverseCover();instances.add(cover);return cover;});
        afterOne.forEachConcurrently(CheckerParallelism.workers(),state->{
            requireStage(state,2);
            int gain=state.pair().size()==ErikssonRoute.ROOT_SYMBOLS-1?1:0;
            roots.addGeneratedPrimitive(ErikssonRoute.ROOT_BUDGET,
                    output -> local.get().forEachResidual(state.pair(),gain,output));
        });
        var result=FinalInverseCover.Counts.empty();
        for(var cover:instances) result=result.plus(cover.counts());
        if(result.sources()!=expectedSources) throw new IllegalStateException("Final inverse cover omitted a source class");
        System.out.printf("Independent final inverse cover: sources=%d sourceSides=%d familyClosedSides=%d oldSupports=%d familyClosedSupports=%d candidateSlots=%d familyClosedCandidateSlots=%d invalidCandidates=%d replayedParents=%d immediateClosedParents=%d residualOccurrences=%d distinctResidualRoots=unknown_until_scan%n",
                result.sources(),result.sourceSides(),result.closedSides(),result.supportChoices(),result.closedSupports(),
                result.candidateSlots(),result.closedCandidateSlots(),result.invalidCandidates(),result.replayedParents(),
                result.immediateClosed(),result.residualParents());
        return result;
    }
    static void verifyFinalRoots(final CertificateStore certificates,final CheckerStateSet roots) {
        roots.forEachConcurrently(CheckerParallelism.workers(),batch->{
            batch.forEach(state->requireStage(state,3));
            return certificates.findAllPrimitive(batch.stream().map(SearchState::pair).toList(),ErikssonRoute.ROOT_BUDGET);
        },state->{},state->{throw missing(state.pair(),ErikssonRoute.ROOT_BUDGET);});
    }
    static void publishInverseCoverage(final Path work,final InverseCoverage result) {
        Path temporary=null;
        try {
            temporary=Files.createTempFile(work,".inverse-coverage-",".tmp");
            var cover=result.finalCover();
            Files.write(temporary,List.of("td20-independent-inverse-coverage-v3","route="+ErikssonRoute.ID,
                    "rootSymbols="+ErikssonRoute.ROOT_SYMBOLS,"rootBudget="+ErikssonRoute.ROOT_BUDGET,
                    "status=complete","mode=residual-expansion","endpointBudget=8","afterTwoBudget=9",
                    "afterOneBudget=10","budgetTenLookup=false","rootInventory=ELEMENTARY_RESIDUALS",
                    "elementaryClosure=two-sided-immediate-gain-at-least-two-v1","endpoints="+result.endpoints(),
                    "afterTwo="+result.afterTwo(),"afterTwoClosed="+result.afterTwoClosed(),
                    "afterTwoResiduals="+result.afterTwoResiduals(),"afterOne="+result.afterOne(),
                    "sourceSides="+cover.sourceSides(),"familyClosedSides="+cover.closedSides(),
                    "oldSupports="+cover.supportChoices(),"familyClosedSupports="+cover.closedSupports(),
                    "candidateSlots="+cover.candidateSlots(),"familyClosedCandidateSlots="+cover.closedCandidateSlots(),
                    "invalidCandidates="+cover.invalidCandidates(),"replayedParents="+cover.replayedParents(),
                    "immediateClosedParents="+cover.immediateClosed(),"residualOccurrences="+cover.residualParents(),
                    "roots="+result.roots(),"unresolved=0"));
            Files.move(temporary,work.resolve("inverse-coverage-v3.txt"),StandardCopyOption.ATOMIC_MOVE);
        } catch(Exception error) { throw new IllegalStateException("Cannot publish independent inverse coverage",error); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(Exception ignored) { } }
    }
    private static void requireStage(SearchState state,int stage) {
        int n=state.pair().size(),budget=ErikssonRoute.ROOT_BUDGET;
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
    private static IllegalStateException missing(final PrimitivePair pair, final int maximumMoves) {
        return new IllegalStateException("Missing sorting certificate: classKey="
                + java.util.HexFormat.of().formatHex(pair.canonicalKey()) + ", maximumMoves=" + maximumMoves);
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
