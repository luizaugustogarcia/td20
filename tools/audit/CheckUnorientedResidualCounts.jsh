// Run from the repository root after compiling the checker:
// jshell --class-path checker/target/classes tools/audit/CheckUnorientedResidualCounts.jsh
import java.nio.file.*;
import java.util.*;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.runtime.CheckerParallelism;

try {
    Path root=Path.of("").toAbsolutePath();
    Path paper=root.resolve("docs/td20-reduction-count.tex");
    Path directory=root.resolve("data/unoriented-joint-residuals-checker-v1");
    Files.createDirectories(directory);
    long totalU=0,totalR=0,totalO=0;
    for(var row:JointInventory.read(paper)) {
        System.out.println("START independent TD20 partition="+row.number());
        long count=IndependentJointRootGenerator.countUnorientedResidualsConcurrently(
                row.partition(),CheckerParallelism.workers(),()->{});
        long expected=JointInventory.read(paper).get(row.number()-1).unorientedResidualClasses();
        if(expected<0)throw new IllegalStateException("Pending residual row "+row.number());
        if(count!=expected)throw new AssertionError("Count mismatch at "+row.number()
                +": independent="+count+" paper="+expected);
        Path temporary=Files.createTempFile(directory,".independent-",".tmp");
        try {
            Files.write(temporary,List.of("td20-unoriented-residual-regeneration-check-v1",
                    "domain=21","number="+row.number(),"partition="+Arrays.toString(row.partition()),
                    "classes="+count,"implementation=independent-arbitrary-supports-v1","complete=true"));
            Files.move(temporary,directory.resolve("partition-"+row.number()+".txt"),
                    StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
        totalU=Math.addExact(totalU,row.unorientedClasses());
        totalR=Math.addExact(totalR,count);
        totalO=Math.addExact(totalO,row.orientedClasses());
        System.out.println("CHECKED independent TD20 partition="+row.number()+" U="+row.unorientedClasses()
                +" R="+count+" filtered="+(row.unorientedClasses()-count));
    }
    System.out.println("COMPLETE independent TD20 U="+totalU+" R="+totalR
            +" filtered="+(totalU-totalR)+" direct="+Math.addExact(totalR,totalO));
} catch(Throwable error) {error.printStackTrace();System.exit(1);}
/exit
