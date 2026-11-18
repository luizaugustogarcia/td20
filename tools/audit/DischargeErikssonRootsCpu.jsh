import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.model.*;
import br.unb.cic.td20.search.*;
import br.unb.cic.td20.certificate.*;

class ErikssonCpuRootDischarge {
    static final int WORKERS=Runtime.getRuntime().availableProcessors();
    static Optional<PrimitiveCertificate> search(PrimitivePair root) throws Exception {
        var direct=CpuPrefixSearch.search(root,ErikssonRoute.ROOT_BUDGET,3,8192);
        if (direct.isPresent()) return direct;
        AtomicReference<PrimitiveCertificate> found=new AtomicReference<>();
        try (var pool=Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> jobs=new ArrayList<>(); int[] beta=root.betaSymbols(), images=root.omegaImages();
            for (int a=0;a<beta.length-2;a++) for (int b=a+1;b<beta.length-1;b++) for (int c=b+1;c<beta.length;c++) {
                var first=new CyclicTargetMoves.Move(beta[a],beta[b],beta[c]);
                jobs.add(pool.submit(()->{
                    if (found.get()!=null) return;
                    var omega=CyclicTargetMoves.applyToOmega(images,first);
                    var current=CyclicTargetMoves.applyToBeta(beta,first);
                    var child=PrimitivePair.of(omega,current);
                    var result=CpuPrefixSearch.search(child,ErikssonRoute.ROOT_BUDGET-1,4,500000);
                    if (result.isPresent()) {
                        var tail=result.orElseThrow(); int[] moves=new int[1+tail.moveCount()];
                        moves[0]=first.a() | (first.b()<<5) | (first.c()<<10);
                        System.arraycopy(tail.packedMoves(),0,moves,1,tail.moveCount());
                        var certificate=new PrimitiveCertificate(tail.kind(),moves);
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
                List<PrimitivePair> roots=Collections.synchronizedList(new ArrayList<>());
                var counts=ErikssonRootGenerator.generatePrimitive(row.partition(),WORKERS,()->{},pair->{
                    if (!SupportClearing.closes(pair)) roots.add(pair.canonicalRepresentative());
                });
                if (counts.unoriented()!=row.unoriented() || counts.oriented()!=row.oriented() || counts.supportCleared()!=row.supportCleared()
                        || roots.size()!=row.directClasses()) throw new IllegalStateException("Incomplete direct-root generation");
                roots.sort(Comparator.comparing(pair->HexFormat.of().formatHex(pair.canonicalKey())));
                var prior=writer.findPrimitiveResults(roots,ErikssonRoute.ROOT_BUDGET);
                for (int index=0;index<roots.size();index++) {
                    var pair=roots.get(index); var certificate=prior.get(index);
                    if (certificate.isEmpty()) certificate=search(pair);
                    if (certificate.isEmpty()) throw new IllegalStateException("CPU search did not discharge a required root: "+HexFormat.of().formatHex(pair.canonicalKey()));
                    var result=certificate.orElseThrow(); CertificateValidator.validate(pair,result,ErikssonRoute.ROOT_BUDGET);
                    writer.enqueue(pair,result,ErikssonRoute.ROOT_BUDGET);
                    writer.flush();
                    closed++;
                    System.out.println("DISCHARGED partition="+row.number()+" classKey="+HexFormat.of().formatHex(pair.canonicalKey())+" kind="+result.kind()+" prefixMoves="+result.moveCount()+" requiredBudget="+CertificateValidator.requiredBudget(pair,result));
                }
                if (writer.findPrimitiveResults(roots,ErikssonRoute.ROOT_BUDGET).stream().anyMatch(Optional::isEmpty))
                    throw new IllegalStateException("Missing persisted root certificate");
            }
        }
        if (closed!=obligations) throw new IllegalStateException("Incomplete CPU root discharge");
        System.out.println("COMPLETE route="+ErikssonRoute.ID+" directRoots="+obligations+" certificates="+closed+" workers="+WORKERS+" gpuUsed=false");
    }
}
int status=1;
try { ErikssonCpuRootDischarge.run(); status=0; } catch (Throwable error) { error.printStackTrace(); status=1; }
/exit status
