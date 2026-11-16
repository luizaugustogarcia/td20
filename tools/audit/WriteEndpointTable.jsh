import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.runtime.ProjectPaths;

class EndpointTableWriter {
    static final String BEGIN="% BEGIN ERIKSSON_ENDPOINT_TABLE_V1";
    static final String END="% END ERIKSSON_ENDPOINT_TABLE_V1";
    static final Path ROOT=ProjectPaths.root(Path.of(""));
    static final String TAG=ErikssonRoute.ID.substring(0,ErikssonRoute.ID.indexOf('-'));
    record Row(ErikssonInventory.Endpoint endpoint,long classes,long requiringDischarge) { }

    static Map<String,String> read(Path path) throws Exception {
        Map<String,String> fields=new TreeMap<>();
        for (String line:Files.readAllLines(path)) {
            String[] parts=line.split("=",2);
            if (parts.length!=2 || fields.putIfAbsent(parts[0],parts[1])!=null)
                throw new IllegalStateException("Malformed endpoint report: "+path);
        }
        return fields;
    }

    static Row row(ErikssonInventory.Endpoint endpoint) throws Exception {
        Map<String,String> expected=new TreeMap<>();
        expected.put("format",TAG+"-eriksson-endpoint-inventory-v1");
        expected.put("route",ErikssonRoute.ID);
        expected.put("domain",Integer.toString(ErikssonRoute.ENDPOINT_SYMBOLS));
        expected.put("budget",Integer.toString(ErikssonRoute.ENDPOINT_BUDGET));
        expected.put("number",Integer.toString(endpoint.number()));
        expected.put("partition",Arrays.toString(endpoint.partition()));
        expected.put("orientations",Arrays.toString(endpoint.orientedByPart()));
        expected.put("elementary",Boolean.toString(endpoint.elementaryClosure()));
        expected.put("complete","true");
        Map<String,String> emitter=read(report("eriksson-endpoint-counts-v1",endpoint.number()));
        Map<String,String> checker=read(report("eriksson-endpoint-counts-checker-v1",endpoint.number()));
        Set<String> keys=new TreeSet<>(expected.keySet()); keys.add("classes");
        if (!emitter.keySet().equals(keys) || !checker.keySet().equals(keys) || !emitter.equals(checker))
            throw new IllegalStateException("Missing or disagreeing endpoint fields: "+endpoint.number());
        for (var entry:expected.entrySet()) if (!entry.getValue().equals(emitter.get(entry.getKey())))
            throw new IllegalStateException("Incompatible endpoint report: "+endpoint.number()+" "+entry.getKey());
        long classes=Long.parseLong(emitter.get("classes"));
        if (classes<0) throw new IllegalStateException("Negative endpoint count");
        return new Row(endpoint,classes,endpoint.elementaryClosure()?0:classes);
    }

    static Path report(String directory,int number) {
        return ROOT.resolve("data/"+directory).resolve(String.format(Locale.ROOT,"endpoint-%03d.txt",number));
    }
    static String number(long value) { return String.format(Locale.ROOT,"%,d",value).replace(",","\\,"); }
    static String type(ErikssonInventory.Endpoint endpoint) {
        int[] parts=endpoint.partition(); boolean[] oriented=endpoint.orientedByPart();
        var text=new StringJoiner(",");
        for (int i=0;i<parts.length;i++) text.add(parts[i]+"_{\\mathrm "+(oriented[i]?"O":"U")+"}");
        return text.toString();
    }
    static String replaceBlock(String source,String begin,String end,String content) {
        int at=source.indexOf(begin);
        if (at<0) return source;
        int finish=source.indexOf(end,at);
        if (finish<0 || source.indexOf(begin,at+begin.length())>=0)
            throw new IllegalStateException("Malformed generated table markers");
        return source.substring(0,at)+content+source.substring(finish+end.length());
    }

    static void run() throws Exception {
        int workers=Runtime.getRuntime().availableProcessors();
        var endpoints=ErikssonInventory.endpoints();
        Set<String> filenames=endpoints.stream().map(e->report("eriksson-endpoint-counts-v1",e.number()).getFileName().toString()).collect(Collectors.toSet());
        for (String directory:List.of("eriksson-endpoint-counts-v1","eriksson-endpoint-counts-checker-v1")) {
            try (var files=Files.list(ROOT.resolve("data/"+directory))) {
                Set<String> actual=files.map(p->p.getFileName().toString()).filter(s->s.startsWith("endpoint-")&&s.endsWith(".txt")).collect(Collectors.toSet());
                if (!actual.equals(filenames)) throw new IllegalStateException("Incomplete endpoint report set: "+directory);
            }
        }
        List<Row> rows=new ArrayList<>();
        try (var pool=Executors.newFixedThreadPool(workers)) {
            List<Future<Row>> jobs=new ArrayList<>();
            for (var endpoint:endpoints) jobs.add(pool.submit(()->row(endpoint)));
            for (var job:jobs) rows.add(job.get());
        }
        long all=0,required=0;
        StringBuilder table=new StringBuilder(BEGIN+"\n");
        table.append("% Route: "+ErikssonRoute.ID+"; checked emitter and checker endpoint-inventory-v1 reports.\n");
        table.append("""
@subsection{Complete endpoint inventory}
In Table~@ref{tab:endpoints}, $k_{@mathrm U}$ and $k_{@mathrm O}$ denote
unoriented and oriented algebraic cycles of length $k$. Let $m$ be the
contracted endpoint domain, $F=5$ the fixed symbols removed, $t=3$ the
transpositions spent, and $b$ the remaining move budget. The column $@kappa$
is the $3$-norm defined above. Equal-length cycles are annotated up to
permutation, and algebraic $2$-cycles are always unoriented. Every annotated
type is included, even when its realizable class count is zero.

``To discharge'' counts classes outside the elementary diameter closures
specified above, before individual certificate lookup. Each needs a result
within $b$ moves or complete inverse coverage. The totals are class obligations,
not observed sorting failures, and exclude additional inverse reconstruction.
All rows agree between the emitter and independent checker census reports.

@begingroup@small@setlength{@tabcolsep}{3pt}
@providecommand{@censusendpointrow}[9]{#1 & $(#2)$ & #3 & #4 & #5 & #6 & #7 & @ensuremath{#8} & @ensuremath{#9}@@}
@begin{longtable}{§{}rlrrrrrrr§{}}
@caption{Complete endpoint class census and non-elementary sorting obligations.}@label{tab:endpoints}@@
@toprule No. & Annotated type & $m$ & $F$ & $t$ & $b$ & $@kappa$ & Classes & To discharge@@@midrule@endfirsthead
@multicolumn{9}{c}{@tablename@ @thetable{} (continued)}@@
@toprule No. & Annotated type & $m$ & $F$ & $t$ & $b$ & $@kappa$ & Classes & To discharge@@@midrule@endhead
@midrule@multicolumn{9}{r}{Continued on next page}@@@endfoot
""".replace("@","\\").replace("§","@"));
        table.append("\\midrule\\multicolumn{7}{r}{\\textbf{Total}} & $\\mathbf{");
        for (var row:rows) { all=Math.addExact(all,row.classes());required=Math.addExact(required,row.requiringDischarge()); }
        table.append(number(all)).append("}$ & $\\mathbf{").append(number(required)).append("}$\\\\\n\\bottomrule\\endlastfoot\n");
        for (var row:rows) {
            int norm=Arrays.stream(row.endpoint().partition()).map(k->k/2).sum();
            List<String> fields=List.of(Integer.toString(row.endpoint().number()),type(row.endpoint()),Integer.toString(ErikssonRoute.ENDPOINT_SYMBOLS),"5","3",Integer.toString(ErikssonRoute.ENDPOINT_BUDGET),Integer.toString(norm),number(row.classes()),number(row.requiringDischarge()));
            table.append("\\censusendpointrow{").append(String.join("}{",fields)).append("}\n");
        }
        table.append("\\end{longtable}\\endgroup\n");
        table.append("Thus $"+number(Math.subtractExact(all,required))+"$ endpoint classes close by\nthese elementary bounds, leaving $"+number(required)+"$ non-elementary\nendpoint obligations. Individual witness reuse and final inverse coverage are\nseparate from this census.\n\n\\subsection{Direct root inventory}\n").append(END);
        Path paper=ROOT.resolve("docs/"+TAG+"-reduction-count.tex");
        String source=Files.readString(paper);
        if (source.contains(BEGIN)) source=replaceBlock(source,BEGIN,END,table.toString());
        else {
            String anchor="Let $U_3,O_3,C_3$ count";
            int at=source.indexOf(anchor);
            if (at<0) throw new IllegalStateException("Missing endpoint table insertion point");
            source=source.substring(0,at)+table+"\n\n"+source.substring(at);
        }
        long u=0,o=0,c=0;
        for (var root:ErikssonInventory.read(paper)) {
            root.requireCompleteCounts();u=Math.addExact(u,root.unoriented());o=Math.addExact(o,root.oriented());c=Math.addExact(c,root.supportCleared());
        }
        String beginRoot="% BEGIN ERIKSSON_ROOT_TOTALS_V1",endRoot="% END ERIKSSON_ROOT_TOTALS_V1";
        String total=beginRoot+"\n\\midrule\\multicolumn{2}{r}{\\textbf{Total}} & "+u+" & "+o+" & "+c+" & "+Math.subtractExact(Math.addExact(u,o),c)+"\\\\\n\\bottomrule\\endlastfoot\n"+endRoot;
        if (source.contains(beginRoot)) source=replaceBlock(source,beginRoot,endRoot,total);
        else {
            int rootTable=source.indexOf("\\caption{Active Eriksson-plus-third-move root inventory.}");
            int footer=source.indexOf("\\bottomrule\\endfoot",rootTable);
            if (rootTable<0 || footer<0) throw new IllegalStateException("Missing root table footer");
            int at=footer+"\\bottomrule\\endfoot".length();source=source.substring(0,at)+"\n"+total+source.substring(at);
        }
        Path temporary=Files.createTempFile(paper.getParent(),".endpoint-table-",".tmp");
        try { Files.writeString(temporary,source);Files.move(temporary,paper,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
        System.out.println("COMPLETE endpointTableRows="+rows.size()+" classes="+all+" requiringDischarge="+required+" workers="+workers+" paper="+paper);
    }
}
int tableStatus=1;
try { EndpointTableWriter.run(); tableStatus=0; } catch(Throwable error) { error.printStackTrace(); }
/exit tableStatus
