#!/usr/bin/env python3
"""TD20-only regression fixture for complete, atomic root-count publication.

After preparing both classpaths with tools/td20 build, run:
    bash -ic 'python3 tools/audit/test_eriksson_root_publication.py'

The maintained JShell census class is loaded without its automatic invocation.
A temporary copy of the paper receives 83 synthetic versioned emitter reports:
row i has U=i, O=2i and C=i, so totals are 3486/6972/3486/6972.
The emitter fixture checks every row, totals, status, idempotency, unchanged
proof text, editorial preservation, rejection of incomplete/incompatible
reports and sections, displaced rows, ordering and overflow, and temp cleanup.
A separate checker JShell verifies that emitter publication is forbidden.
All generated scripts, reports, and paper changes remain in a temporary tree;
this does not perform a real census, sorting run, or TD20 proof verification.
"""
from __future__ import annotations

from pathlib import Path
import shlex
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[2]

PUBLICATION_FIXTURE = r'''
class PublicationFixture {
    static final String BS=String.valueOf((char)92);
    static final String NL=String.valueOf((char)10);
    static final Path PAPER=Path.of("docs/td20-reduction-count.tex");
    static final List<int[]> PARTS=Partitions.generateValidPartitions(ErikssonRoute.ROOT_SYMBOLS);
    static int checks;
    static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); checks++; }
    static void failure(Runnable action,String label) throws Exception {
        String before=Files.readString(PAPER); boolean rejected=false;
        try { action.run(); } catch (RuntimeException expected) { rejected=true; }
        check(rejected,label+" must reject");
        check(before.equals(Files.readString(PAPER)),label+" must leave the paper unchanged");
    }
    static void publishUnchecked() {
        try { ActiveErikssonRootCensus.publish(PAPER,PARTS); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    static void run() throws Exception {
        System.out.println("SYNTHETIC FIXTURE ONLY: no real counts or real paper are written");
        System.setProperty("td20.eriksson.publish","true");
        Files.createDirectories(ActiveErikssonRootCensus.OUTPUT);
        for (int i=0;i<PARTS.size();i++) {
            int index=i+1;
            var values=ActiveErikssonRootCensus.scope(index,PARTS.get(i));
            values.put("unoriented",Integer.toString(index));
            values.put("oriented",Integer.toString(2*index));
            values.put("supportCleared",Integer.toString(index));
            values.put("prefixNodes",Integer.toString(3*index));
            values.put("realizableLeaves",Integer.toString(4*index));
            values.put("workers",Integer.toString(Runtime.getRuntime().availableProcessors()));
            values.put("elapsedNanos","0");
            ActiveErikssonRootCensus.write(ActiveErikssonRootCensus.OUTPUT.resolve(String.format("partition-%02d.txt",index)),values);
        }
        String original=Files.readString(PAPER);
        String proofStart=BS+"begin{theorem}[Transposition diameter]";
        String proof=original.substring(original.indexOf(proofStart));
        ActiveErikssonRootCensus.run();
        String published=Files.readString(PAPER);
        var rows=ErikssonInventory.read(PAPER);
        check(rows.size()==83 && rows.stream().allMatch(ErikssonInventory.Root::countsComplete),"all 83 rows must be complete");
        for (var row:rows) check(row.unoriented()==row.number() && row.oriented()==2*row.number() && row.supportCleared()==row.number(),"row count differs");
        String totalPrefix=BS+"midrule"+BS+"multicolumn{2}{r}{"+BS+"textbf{Total}} & ";
        String u="$"+BS+"mathbf{3"+BS+",486}$", o="$"+BS+"mathbf{6"+BS+",972}$";
        check(published.contains(totalPrefix+u+" & "+o+" & "+u+" & "+o+BS+BS),"aggregate totals differ");
        check(!published.contains("Eriksson-route counts are pending"),"pending-count prose survives");
        check(published.contains("All 83 root partitions below have complete versioned emitter counts."),"completed-count status missing");
        check(published.substring(published.indexOf(proofStart)).equals(proof),"proof status changed");
        ActiveErikssonRootCensus.publish(PAPER,PARTS);
        check(published.equals(Files.readString(PAPER)),"publication must be idempotent");
        String withEditorial=published.replace(BS+"begin{longtable}{@{}rlrrrr@{}}","% PRESERVE EDITORIAL TEXT"+NL+BS+"begin{longtable}{@{}rlrrrr@{}}");
        Files.writeString(PAPER,withEditorial);
        ActiveErikssonRootCensus.publish(PAPER,PARTS);
        check(withEditorial.equals(Files.readString(PAPER)),"publication must preserve extra editorial material outside the known status paragraph");
        Files.writeString(PAPER,published);
        Path report=ActiveErikssonRootCensus.OUTPUT.resolve("partition-83.txt");
        byte[] reportBytes=Files.readAllBytes(report);
        Files.delete(report);
        failure(PublicationFixture::publishUnchecked,"missing report");
        Files.write(report,reportBytes);
        for (String invalid:new String[]{"format=foreign","complete=false","module=checker","unoriented=-1","supportCleared=999999"}) {
            String key=invalid.substring(0,invalid.indexOf('='));
            String text=new String(reportBytes,java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^"+key+"=.*$",invalid);
            Files.writeString(report,text);
            failure(PublicationFixture::publishUnchecked,invalid);
            Files.write(report,reportBytes);
        }
        Files.writeString(report,new String(reportBytes,java.nio.charset.StandardCharsets.UTF_8)+"format=duplicate\n");
        failure(PublicationFixture::publishUnchecked,"duplicate report field");
        Files.write(report,reportBytes);
        for (String changed:new String[]{
                published.replace("% BEGIN ERIKSSON_ROOT_TOTALS_V1","% NO ROOT TOTALS MARKER"),
                published+"\n% BEGIN ERIKSSON_ROOT_TOTALS_V1\n",
                published.replace(BS+"bottomrule"+BS+"endlastfoot\n% END ERIKSSON_ROOT_TOTALS_V1","% END ERIKSSON_ROOT_TOTALS_V1"),
                published.replace("All 83 root partitions below","Unmarked root inventory"),
                published.replace("The independent checker regenerates","The altered checker regenerates")}) {
            Files.writeString(PAPER,changed);
            failure(PublicationFixture::publishUnchecked,"incompatible publication markers");
        }
        Files.writeString(PAPER,published);
        String firstRow=BS+"erikssonrow{1}{21}{1}{2}{1}";
        String lastRow=BS+"erikssonrow{83}{3,3,3,2,2,2,2,2,2}{83}{166}{83}";
        String tableHeader=BS+"begin{longtable}{@{}rlrrrr@{}}";
        for(String displaced:new String[]{
                published.replace(firstRow+NL,"").replace(tableHeader,firstRow+NL+tableHeader),
                published.replace(lastRow+NL,"")+lastRow+NL}) {
            Files.writeString(PAPER,displaced);
            failure(PublicationFixture::publishUnchecked,"root row outside its table");
        }
        Files.writeString(PAPER,published);
        for(String key:new String[]{"workers","unoriented","format"}) {
            String text=new String(reportBytes,java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^"+key+"=.*\n","");
            Files.writeString(report,text);
            failure(PublicationFixture::publishUnchecked,"missing field "+key);
            Files.write(report,reportBytes);
        }
        Files.writeString(report,new String(reportBytes,java.nio.charset.StandardCharsets.UTF_8)+"unexpected=value"+NL);
        failure(PublicationFixture::publishUnchecked,"unknown report field");
        Files.write(report,reportBytes);
        Files.writeString(report,new String(reportBytes,java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^unoriented=.*$","unoriented="+Long.MAX_VALUE));
        failure(PublicationFixture::publishUnchecked,"overflowed aggregate count");
        Files.write(report,reportBytes);
        var wrongOrder=new ArrayList<int[]>(PARTS);
        Collections.swap(wrongOrder,0,1);
        failure(()->{try {ActiveErikssonRootCensus.publish(PAPER,wrongOrder);} catch(Exception error){throw new IllegalStateException(error);}},"partition-order mismatch");
        try (var paths=Files.list(PAPER.toAbsolutePath().getParent())) {
            check(paths.noneMatch(p->p.getFileName().toString().startsWith(".active-counts-")),"temporary publication file leaked");
        }
        System.out.println("PUBLICATION FIXTURE PASSED checks="+checks+" rows=83 syntheticTotals=3486/6972/3486/6972 actualCountsGenerated=false");
    }
}
int fixtureStatus=1;
try {PublicationFixture.run(); fixtureStatus=0;} catch(Throwable error){error.printStackTrace();fixtureStatus=1;}
/exit fixtureStatus
'''

CHECKER_GUARD = r'''
System.setProperty("td20.countModule","checker");
int guardStatus=1;
try {
    var paper=Path.of("docs/td20-reduction-count.tex");
    var before=Files.readAllBytes(paper);
    boolean rejected=false;
    try {ActiveErikssonRootCensus.publish(paper,Partitions.generateValidPartitions(ErikssonRoute.ROOT_SYMBOLS));}
    catch(IllegalArgumentException expected) {rejected=expected.getMessage().equals("The checker cannot publish emitter counts");}
    if(!rejected || !Arrays.equals(before,Files.readAllBytes(paper))) throw new AssertionError("checker publication guard failed");
    System.out.println("CHECKER PUBLICATION GUARD PASSED paperUnchanged=true");
    guardStatus=0;
} catch(Throwable error) {error.printStackTrace();guardStatus=1;}
/exit guardStatus
'''


def census_definition() -> str:
    source = (PROJECT / "tools/audit/CountErikssonRoots.jsh").read_text()
    marker = "\nint censusStatus=1;\n"
    if source.count(marker) != 1:
        raise AssertionError("Cannot safely separate the census class from its automatic run")
    return source.split(marker, 1)[0] + "\n"


def prepared_classpath(module: str) -> str:
    classes = PROJECT / module / "target/classes"
    dependencies = PROJECT / module / "target/jshell-classpath"
    if not classes.is_dir() or not dependencies.is_file():
        raise AssertionError("Prepare both JShell classpaths with tools/td20 build first")
    return str(classes) + ":" + dependencies.read_text().strip()


def run_fixture(directory: Path, source: str, name: str, fixture: str,
                module: str, expected: str) -> None:
    driver = directory / (name + ".jsh")
    driver.write_text(source + fixture)
    command = shlex.join(("jshell", "-R-Dtd20.countModule=" + module,
                          "--feedback", "concise", "--class-path",
                          prepared_classpath(module), str(driver)))
    result = subprocess.run(("bash", "-ic", command), cwd=directory,
                            text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=60)
    if result.returncode != 0 or expected not in result.stdout or "\nError:" in result.stdout:
        raise AssertionError(f"{name} failed (exit {result.returncode}):\n{result.stdout}")
    print(expected)


def main() -> None:
    source = census_definition()
    paper = (PROJECT / "docs/td20-reduction-count.tex").read_bytes()
    with tempfile.TemporaryDirectory(prefix="td20-root-publication-regression-") as temporary:
        directory = Path(temporary)
        (directory / "docs").mkdir()
        (directory / "docs/td20-reduction-count.tex").write_bytes(paper)
        run_fixture(directory, source, "emitter-publication", PUBLICATION_FIXTURE,
                    "emitter", "PUBLICATION FIXTURE PASSED checks=131 rows=83 "
                    "syntheticTotals=3486/6972/3486/6972 actualCountsGenerated=false")
        run_fixture(directory, source, "checker-publication-guard", CHECKER_GUARD,
                    "checker", "CHECKER PUBLICATION GUARD PASSED paperUnchanged=true")


if __name__ == "__main__":
    main()
