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
    static int uniqueLine(List<String> lines,String marker,boolean prefix) {
        int found=-1;
        for (int i=0;i<lines.size();i++) {
            String line=lines.get(i).strip();
            if (!(prefix ? line.startsWith(marker):line.equals(marker))) continue;
            if (found!=-1) throw new IllegalStateException("Duplicate root publication marker: "+marker);
            found=i;
        }
        if (found==-1) throw new IllegalStateException("Missing root publication marker: "+marker);
        return found;
    }
    static String tableNumber(long value) {
        return "$\\mathbf{"+String.format(Locale.ROOT,"%,d",value).replace(",","\\,")+"}$";
    }
    /**
     * TD20 starts with pending rows, totals and editorial status. The TD18
     * row-only publisher leaves those parts inconsistent. Validate the entire
     * versioned inventory before atomically replacing all three count sections;
     * publication never asserts completed sorting or a proved TD20 upper bound.
     */
    static void publish(Path paper,List<int[]> parts) throws Exception {
        if (CHECKER) throw new IllegalArgumentException("The checker cannot publish emitter counts");
        var rows=ErikssonInventory.read(paper);
        if (parts.size()!=ErikssonRoute.ROOT_PARTITIONS || rows.size()!=parts.size())
            throw new IllegalStateException("Incomplete active publication inventory");
        var counts=new ArrayList<ErikssonRootGenerator.Counts>();
        long totalU=0,totalO=0,totalC=0;
        for (int i=0;i<parts.size();i++) {
            if (!Arrays.equals(parts.get(i),rows.get(i).partition()))
                throw new IllegalStateException("Publication partition differs at row "+(i+1));
            var count=validate(i+1,parts.get(i),read(OUTPUT.resolve(String.format("partition-%02d.txt",i+1))));
            counts.add(count);
            totalU=Math.addExact(totalU,count.unoriented());
            totalO=Math.addExact(totalO,count.oriented());
            totalC=Math.addExact(totalC,count.supportCleared());
        }
        long totalDirect=Math.subtractExact(Math.addExact(totalU,totalO),totalC);
        var lines=new ArrayList<>(Files.readAllLines(paper));
        int status=uniqueLine(lines,"All "+parts.size()+" root partitions below",true);
        int table=uniqueLine(lines,"\\begin{longtable}{@{}rlrrrr@{}}",false);
        int begin=uniqueLine(lines,"% BEGIN ERIKSSON_ROOT_TOTALS_V1",false);
        int end=uniqueLine(lines,"% END ERIKSSON_ROOT_TOTALS_V1",false);
        int tableEnd=-1;
        for (int i=table+1;i<lines.size();i++) {
            String line=lines.get(i).strip();
            if (line.startsWith("\\begin{longtable}")) throw new IllegalStateException("Nested active root table");
            if (line.equals("\\end{longtable}")) { tableEnd=i; break; }
        }
        if (!(status<table && table<begin && end==begin+3 && end<tableEnd)
                || !lines.get(begin+1).strip().startsWith("\\midrule\\multicolumn{2}{r}{\\textbf{Total}} & ")
                || !lines.get(begin+2).strip().equals("\\bottomrule\\endlastfoot"))
            throw new IllegalStateException("Malformed active root publication sections");
        var publishedStatus=List.of(
                "All "+parts.size()+" root partitions below have complete versioned emitter counts.",
                "The independent checker regenerates every retained unoriented count $U_3$,",
                "retained oriented count $O_3$ and support-cleared count $C_3$, comparing",
                "them with these rows. The aggregate totals are sums of the published rows.",
                "Counting discharges no sorting obligation or full proof-verification requirement.");
        var previousStatus=lines.get(status).strip().equals(publishedStatus.getFirst()) ? publishedStatus:List.of(
                "All "+parts.size()+" root partitions below have been regenerated structurally. Their new",
                "Eriksson-route counts are pending; historical joint-2 counts cannot populate",
                "these rows. Both modules must independently regenerate every count. Sorting",
                "verification is a separate requirement.");
        int statusEnd=status+previousStatus.size();
        if (statusEnd>table || !lines.subList(status,statusEnd).stream().map(String::strip).toList().equals(previousStatus))
            throw new IllegalStateException("Incompatible active root count status");
        int replaced=0;
        for (int i=0;i<lines.size();i++) {
            String line=lines.get(i).strip();
            if (!line.startsWith("\\erikssonrow{") && !line.startsWith("\\erikssonpendingrow{")) continue;
            if (i<=end || i>=tableEnd) throw new IllegalStateException("Active root row outside its table");
            int index=++replaced; var count=counts.get(index-1);
            lines.set(i,"\\erikssonrow{"+index+"}{"+String.join(",",Arrays.stream(parts.get(index-1)).mapToObj(Integer::toString).toList())
                    +"}{"+count.unoriented()+"}{"+count.oriented()+"}{"+count.supportCleared()+"}");
        }
        if (replaced!=parts.size()) throw new IllegalStateException("Incomplete active publication inventory");
        lines.set(begin+1,"\\midrule\\multicolumn{2}{r}{\\textbf{Total}} & "+tableNumber(totalU)+" & "
                +tableNumber(totalO)+" & "+tableNumber(totalC)+" & "+tableNumber(totalDirect)+"\\\\");
        lines.subList(status,statusEnd).clear();
        lines.addAll(status,publishedStatus);
        Path temporary=Files.createTempFile(paper.toAbsolutePath().getParent(),".active-counts-",".tmp");
        try {
            Files.write(temporary,lines);
            var published=ErikssonInventory.read(temporary);
            for (int i=0;i<parts.size();i++) {
                var row=published.get(i); var count=counts.get(i);
                row.requireCompleteCounts();
                if (row.unoriented()!=count.unoriented() || row.oriented()!=count.oriented() || row.supportCleared()!=count.supportCleared())
                    throw new IllegalStateException("Published count differs at row "+(i+1));
            }
            Files.move(temporary,paper,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
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
        if (Boolean.getBoolean("td20.eriksson.publish")) publish(paper,parts);
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
