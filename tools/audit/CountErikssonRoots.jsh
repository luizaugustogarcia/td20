import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.reduction.Partitions;

class ActiveErikssonRootCensus {
    static final int WORKERS=Runtime.getRuntime().availableProcessors();
    static final boolean CHECKER=System.getProperty("td20.countModule","emitter").equals("checker");
    static final Path OUTPUT=Path.of(CHECKER ? "data/eriksson-root-counts-checker-v1":"data/eriksson-root-counts-v1");
    static final String FORMAT="td20-eriksson-root-inventory-v1";
    static Map<String,String> scope(int number,int[] part) {
        var values=new TreeMap<String,String>();
        values.put("format",FORMAT); values.put("route",ErikssonRoute.ID);
        values.put("module",CHECKER ? "checker":"emitter"); values.put("domain",Integer.toString(ErikssonRoute.ROOT_SYMBOLS));
        values.put("budget",Integer.toString(ErikssonRoute.ROOT_BUDGET)); values.put("number",Integer.toString(number));
        values.put("partition",Arrays.toString(part)); values.put("complete","true");
        return values;
    }
    static long number(Map<String,String> values,String key) {
        long value=Long.parseLong(Objects.requireNonNull(values.get(key),"Missing "+key));
        if (value<0) throw new IllegalStateException("Negative "+key); return value;
    }
    static Map<String,String> read(Path path) throws Exception {
        var values=new TreeMap<String,String>();
        for (String line:Files.readAllLines(path)) {
            String[] fields=line.split("=",2);
            if (fields.length!=2 || values.putIfAbsent(fields[0],fields[1])!=null) throw new IllegalStateException("Malformed census: "+path);
        }
        return values;
    }
    static ErikssonRootGenerator.Counts validate(int index,int[] part,Map<String,String> values) {
        var expected=scope(index,part);
        for (var entry:expected.entrySet()) if (!entry.getValue().equals(values.get(entry.getKey())))
            throw new IllegalStateException("Incompatible or missing census field: "+entry.getKey());
        var keys=new HashSet<String>(expected.keySet());
        keys.addAll(List.of("unoriented","oriented","supportCleared","prefixNodes","realizableLeaves","workers","elapsedNanos"));
        if (!keys.equals(values.keySet()) || number(values,"workers")==0) throw new IllegalStateException("Incomplete census fields");
        number(values,"elapsedNanos");
        var counts=new ErikssonRootGenerator.Counts(number(values,"unoriented"),number(values,"oriented"),number(values,"supportCleared"),number(values,"prefixNodes"),number(values,"realizableLeaves"));
        if (counts.supportCleared()>counts.oriented()) throw new IllegalStateException("Invalid cleared count");
        return counts;
    }
    static void write(Path path,Map<String,String> values) throws Exception {
        Path temporary=Files.createTempFile(OUTPUT,".root-",".tmp");
        try {
            Files.write(temporary,new TreeMap<>(values).entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList());
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void run() throws Exception {
        Files.createDirectories(OUTPUT);
        var parts=Partitions.generateValidPartitions(ErikssonRoute.ROOT_SYMBOLS);
        if (parts.size()!=ErikssonRoute.ROOT_PARTITIONS) throw new IllegalStateException("Incomplete partition inventory");
        LongAdder progress=new LongAdder(); AtomicInteger partition=new AtomicInteger();
        long totalU=0,totalO=0,totalC=0;
        System.out.println("START "+FORMAT+" module="+(CHECKER ? "checker":"emitter")+" workers="+WORKERS);
        try (var reporter=Executors.newSingleThreadScheduledExecutor()) {
            reporter.scheduleAtFixedRate(() -> System.out.println("PROGRESS partition="+partition.get()+" prefixNodesApprox="+progress.sum()),10,10,TimeUnit.SECONDS);
            for (int i=0;i<parts.size();i++) {
                int index=i+1; int[] part=parts.get(i); partition.set(index); progress.reset();
                Path path=OUTPUT.resolve(String.format("partition-%02d.txt",index));
                ErikssonRootGenerator.Counts counts;
                if (Files.exists(path)) counts=validate(index,part,read(path));
                else {
                    System.out.println("START partition="+index+" lengths="+Arrays.toString(part));
                    long started=System.nanoTime(); counts=ErikssonRootGenerator.count(part,WORKERS,progress);
                    var values=scope(index,part);
                    values.put("unoriented",Long.toString(counts.unoriented())); values.put("oriented",Long.toString(counts.oriented()));
                    values.put("supportCleared",Long.toString(counts.supportCleared())); values.put("prefixNodes",Long.toString(counts.prefixNodes()));
                    values.put("realizableLeaves",Long.toString(counts.realizableLeaves())); values.put("workers",Integer.toString(WORKERS));
                    values.put("elapsedNanos",Long.toString(System.nanoTime()-started)); validate(index,part,values); write(path,values);
                }
                totalU=Math.addExact(totalU,counts.unoriented()); totalO=Math.addExact(totalO,counts.oriented()); totalC=Math.addExact(totalC,counts.supportCleared());
                System.out.println("COMPLETE partition="+index+" unoriented="+counts.unoriented()+" oriented="+counts.oriented()+" cleared="+counts.supportCleared()+" direct="+counts.direct());
            }
            reporter.shutdownNow();
        }
        Path paper=Path.of("docs/td20-reduction-count.tex");
        if (Boolean.getBoolean("td20.eriksson.publish")) {
            if (CHECKER) throw new IllegalArgumentException("The checker cannot publish emitter counts");
            var rows=ErikssonInventory.read(paper); var lines=Files.readAllLines(paper); int replaced=0;
            for (int i=0;i<lines.size();i++) if (lines.get(i).startsWith("\\erikssonrow{") || lines.get(i).startsWith("\\erikssonpendingrow{")) {
                int index=++replaced; var counts=validate(index,parts.get(index-1),read(OUTPUT.resolve(String.format("partition-%02d.txt",index))));
                lines.set(i,"\\erikssonrow{"+index+"}{"+String.join(",",Arrays.stream(parts.get(index-1)).mapToObj(Integer::toString).toList())+"}{"+counts.unoriented()+"}{"+counts.oriented()+"}{"+counts.supportCleared()+"}");
            }
            if (replaced!=parts.size()) throw new IllegalStateException("Incomplete active publication inventory");
            Path temporary=Files.createTempFile(paper.toAbsolutePath().getParent(),".active-counts-",".tmp");
            try { Files.write(temporary,lines); Files.move(temporary,paper,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temporary); }
        }
        if (CHECKER) for (var row:ErikssonInventory.read(paper)) {
            row.requireCompleteCounts(); var counts=validate(row.number(),row.partition(),read(OUTPUT.resolve(String.format("partition-%02d.txt",row.number()))));
            if (row.unoriented()!=counts.unoriented() || row.oriented()!=counts.oriented() || row.supportCleared()!=counts.supportCleared())
                throw new IllegalStateException("Independent count differs at partition "+row.number());
        }
        System.out.println("COMPLETE partitions="+parts.size()+" unoriented="+totalU+" oriented="+totalO+" supportCleared="+totalC+" direct="+(totalU+totalO-totalC)+" sortingChecked=false");
    }
}
int censusStatus=1;
try { ActiveErikssonRootCensus.run(); censusStatus=0; } catch (Throwable error) { error.printStackTrace(); censusStatus=1; }
/exit censusStatus
