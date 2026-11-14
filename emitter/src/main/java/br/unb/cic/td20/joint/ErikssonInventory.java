package br.unb.cic.td20.joint;

import br.unb.cic.td20.reduction.AnnotatedPartitions;
import br.unb.cic.td20.reduction.Partitions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Strict active-route inventory. Every required row must be present and valid. */
public final class ErikssonInventory {
    private ErikssonInventory() { }
    public record Root(int number,int[] partition,long unoriented,long oriented,long supportCleared) {
        public Root {
            partition=partition.clone();
            boolean pending=unoriented==-1 && oriented==-1 && supportCleared==-1;
            if (number<1 || !pending && (unoriented<0 || oriented<0 || supportCleared<0 || supportCleared>oriented))
                throw new IllegalArgumentException("Invalid Eriksson root counts");
        }
        @Override public int[] partition() { return partition.clone(); }
        public boolean countsComplete() { return unoriented>=0; }
        public void requireCompleteCounts() {
            if (!countsComplete()) throw new IllegalStateException("Pending Eriksson count at partition "+number);
        }
        public long directClasses() { requireCompleteCounts(); return Math.subtractExact(Math.addExact(unoriented,oriented),supportCleared); }
    }
    public record Endpoint(int number,int[] partition,boolean[] orientedByPart) {
        public Endpoint { partition=partition.clone(); orientedByPart=orientedByPart.clone(); }
        @Override public int[] partition() { return partition.clone(); }
        @Override public boolean[] orientedByPart() { return orientedByPart.clone(); }
        public boolean elementaryClosure() {
            if (KnownDiameters.knownDiameter(ErikssonRoute.ENDPOINT_SYMBOLS)<=ErikssonRoute.ENDPOINT_BUDGET) return true;
            for (int i=0;i<partition.length;i++) if (partition[i]==3 && orientedByPart[i])
                return 1+KnownDiameters.knownDiameter(ErikssonRoute.ENDPOINT_SYMBOLS-3)<=ErikssonRoute.ENDPOINT_BUDGET;
            return false;
        }
    }
    public static List<Root> read(Path paper) throws IOException {
        var expected=Partitions.generateValidPartitions(ErikssonRoute.ROOT_SYMBOLS);
        var complete=Pattern.compile("^\\\\erikssonrow\\{([1-9][0-9]*)}\\{([0-9,]+)}\\{([0-9]+)}\\{([0-9]+)}\\{([0-9]+)}$");
        var pending=Pattern.compile("^\\\\erikssonpendingrow\\{([1-9][0-9]*)}\\{([0-9,]+)}$");
        List<Root> rows=new ArrayList<>();
        for (String text:Files.readAllLines(paper)) {
            String line=text.strip();
            if (!line.startsWith("\\erikssonrow{") && !line.startsWith("\\erikssonpendingrow{")) continue;
            var matcher=complete.matcher(line); boolean done=matcher.matches();
            if (!done) { matcher=pending.matcher(line); if (!matcher.matches()) throw new IllegalArgumentException("Malformed active row: "+line); }
            int number=Integer.parseInt(matcher.group(1));
            int[] part=Arrays.stream(matcher.group(2).split(",")).mapToInt(Integer::parseInt).toArray();
            if (number!=rows.size()+1 || number>expected.size() || !Arrays.equals(part,expected.get(number-1)))
                throw new IllegalArgumentException("Missing, duplicate or out-of-order active root row");
            rows.add(new Root(number,part,done ? Long.parseLong(matcher.group(3)):-1,
                    done ? Long.parseLong(matcher.group(4)):-1,done ? Long.parseLong(matcher.group(5)):-1));
        }
        if (rows.size()!=ErikssonRoute.ROOT_PARTITIONS || rows.size()!=expected.size())
            throw new IllegalArgumentException("Incomplete Eriksson root inventory");
        return List.copyOf(rows);
    }
    public static List<Endpoint> endpoints() {
        List<Endpoint> rows=new ArrayList<>();
        for (int[] part:Partitions.generateValidPartitions(ErikssonRoute.ENDPOINT_SYMBOLS))
            for (var annotation:AnnotatedPartitions.of(part)) rows.add(new Endpoint(rows.size()+1,part,annotation.oriented()));
        if (rows.size()!=ErikssonRoute.ENDPOINT_TYPES) throw new IllegalStateException("Wrong active endpoint inventory");
        return List.copyOf(rows);
    }
}
