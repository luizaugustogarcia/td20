import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.model.*;
import br.unb.cic.td20.permutation.*;
import br.unb.cic.td20.search.*;
import br.unb.cic.td20.certificate.*;

class ErikssonCpuRootDischarge {
    static final int WORKERS=Runtime.getRuntime().availableProcessors();
    static Optional<SortingCertificate> search(CyclicTargetPair root) throws Exception {
        var direct=CpuPrefixSearch.search(root,ErikssonRoute.ROOT_BUDGET,3,8192);
        if (direct.isPresent()) return direct;
        AtomicReference<SortingCertificate> found=new AtomicReference<>();
        try (var pool=Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> jobs=new ArrayList<>(); int[] beta=root.getBeta().getSymbols();
            for (int a=0;a<beta.length-2;a++) for (int b=a+1;b<beta.length-1;b++) for (int c=b+1;c<beta.length;c++) {
                Cycle first=Cycle.of(beta[a],beta[b],beta[c]);
                jobs.add(pool.submit(()->{
                    if (found.get()!=null) return;
                    var omega=PermutationGroups.computeProduct(root.getOmega(),first.getInverse());
                    var current=CommonOperations.applyTranspositionOptimized(root.getBeta(),first);
                    var child=CyclicTargetPair.ofOwned(omega,current);
                    var result=CpuPrefixSearch.search(child,ErikssonRoute.ROOT_BUDGET-1,4,500000);
                    if (result.isPresent()) {
                        List<Cycle> moves=new ArrayList<>(); moves.add(first); moves.addAll(result.orElseThrow().moves());
                        var certificate=new SortingCertificate(result.orElseThrow().kind(),moves);
                        CertificateValidator.validate(root,certificate,ErikssonRoute.ROOT_BUDGET);
                        found.compareAndSet(null,certificate);
                    }
                }));
            }
            for (var job:jobs) job.get();
        }
        return Optional.ofNullable(found.get());
    }
    static void run() throws Exception {
        Path paper=Path.of("docs/td20-reduction-count.tex"),database=Path.of("certificates");
        var inventory=ErikssonInventory.read(paper);
        long expected=0;
        for (var row:inventory) { row.requireCompleteCounts(); expected=Math.addExact(expected,row.directClasses()); }
        long limit=Long.getLong("td20.cpuRootAuditMaxClasses",100L);
        if (limit<1 || expected>limit) throw new IllegalStateException("CPU audit inventory exceeds its bounded class limit; use WitnessEmitter for larger runs");
        long obligations=0,closed=0;
        try (var writer=new AsyncCertificateWriter(database,1)) {
            for (var row:inventory) {
                row.requireCompleteCounts(); if (row.directClasses()==0) continue;
                obligations=Math.addExact(obligations,row.directClasses());
                List<CyclicTargetPair> roots=Collections.synchronizedList(new ArrayList<>());
                var counts=ErikssonRootGenerator.generate(row.partition(),WORKERS,()->{},pair->{
                    if (!SupportClearing.closes(pair)) roots.add(pair.canonicalRepresentative());
                });
                if (counts.unoriented()!=row.unoriented() || counts.oriented()!=row.oriented() || counts.supportCleared()!=row.supportCleared()
                        || roots.size()!=row.directClasses()) throw new IllegalStateException("Incomplete direct-root generation");
                roots.sort(Comparator.comparing(pair->pair.extendedToricClassKey().toString()));
                for (var pair:roots) {
                    var certificate=writer.findResult(pair,ErikssonRoute.ROOT_BUDGET);
                    if (certificate.isEmpty()) certificate=search(pair);
                    if (certificate.isEmpty()) throw new IllegalStateException("CPU search did not discharge a required root: "+pair.extendedToricClassKey());
                    var result=certificate.orElseThrow(); CertificateValidator.validate(pair,result,ErikssonRoute.ROOT_BUDGET);
                    if (result.kind()==SortingCertificate.Kind.COMPLETE) writer.enqueue(pair,result.moves());
                    else writer.enqueueClosure(pair,result.moves(),ErikssonRoute.ROOT_BUDGET);
                    writer.flush();
                    if (writer.findResult(pair,ErikssonRoute.ROOT_BUDGET).isEmpty()) throw new IllegalStateException("Missing persisted root certificate");
                    closed++;
                    System.out.println("DISCHARGED partition="+row.number()+" classKey="+pair.extendedToricClassKey()+" kind="+result.kind()+" prefixMoves="+result.moves().size()+" requiredBudget="+CertificateValidator.requiredBudget(pair,result));
                }
            }
        }
        if (closed!=obligations) throw new IllegalStateException("Incomplete CPU root discharge");
        System.out.println("COMPLETE route="+ErikssonRoute.ID+" directRoots="+obligations+" certificates="+closed+" workers="+WORKERS+" gpuUsed=false");
    }
}
int status=1;
try { ErikssonCpuRootDischarge.run(); status=0; } catch (Throwable error) { error.printStackTrace(); status=1; }
/exit status
