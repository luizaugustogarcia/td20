package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import java.nio.file.*;
import java.util.*;

/** Optional emitter state, never a proof input. Only the latest sealed wave is resumed. */
public record ErikssonCheckpoint(Stage stage,long endpoints,long afterTwo,long afterOne,long roots,Mode mode) {
    public enum Stage { AFTER_TWO,AFTER_ONE,ROOTS }
    public enum Mode { FULL_SEARCH,RESIDUAL_EXPANSION }
    private static final String FILE="eriksson-reconstruction-v1.txt";
    public ErikssonCheckpoint(Stage stage,long endpoints,long afterTwo,long afterOne,long roots) {
        this(stage,endpoints,afterTwo,afterOne,roots,Mode.FULL_SEARCH);
    }
    public ErikssonCheckpoint {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(mode);
        if (endpoints<0 || afterTwo<0 || stage!=Stage.AFTER_TWO && afterOne<0 || stage==Stage.ROOTS && roots<0
                || stage==Stage.AFTER_TWO && (afterOne!=-1 || roots!=-1) || stage==Stage.AFTER_ONE && roots!=-1)
            throw new IllegalArgumentException("Incomplete or inconsistent sealed-wave inventory");
    }
    public void write(Path directory) {
        Path temporary=null;
        try {
            temporary=Files.createTempFile(directory,".eriksson-checkpoint-",".tmp");
            Files.write(temporary,List.of("td-eriksson-reconstruction-v2","route="+ErikssonRoute.ID,
                    "rootSymbols="+ErikssonRoute.ROOT_SYMBOLS,"rootBudget="+ErikssonRoute.ROOT_BUDGET,
                    "stage="+stage,"endpoints="+endpoints,"afterTwo="+afterTwo,"afterOne="+afterOne,"roots="+roots,"mode="+mode));
            Files.move(temporary,directory.resolve(FILE),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new IllegalStateException("Cannot persist sealed Eriksson wave",error); }
        finally { if (temporary!=null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }
    public static ErikssonCheckpoint read(Path directory) {
        try {
            List<String> values=Files.readAllLines(directory.resolve(FILE));
            boolean legacy=values.size()==9 && values.get(0).equals("td-eriksson-reconstruction-v1");
            boolean current=values.size()==10 && values.get(0).equals("td-eriksson-reconstruction-v2");
            if ((!legacy && !current)
                    || !values.get(1).equals("route="+ErikssonRoute.ID)
                    || !values.get(2).equals("rootSymbols="+ErikssonRoute.ROOT_SYMBOLS)
                    || !values.get(3).equals("rootBudget="+ErikssonRoute.ROOT_BUDGET))
                throw new IllegalArgumentException("Checkpoint belongs to a different route or budget");
            String[] keys={"stage","endpoints","afterTwo","afterOne","roots"};
            for (int i=0;i<keys.length;i++) if (!values.get(i+4).startsWith(keys[i]+"="))
                throw new IllegalArgumentException("Missing checkpoint field: "+keys[i]);
            if(current && !values.get(9).startsWith("mode=")) throw new IllegalArgumentException("Missing checkpoint mode");
            return new ErikssonCheckpoint(Stage.valueOf(values.get(4).substring(6)),Long.parseLong(values.get(5).substring(10)),
                    Long.parseLong(values.get(6).substring(9)),Long.parseLong(values.get(7).substring(9)),Long.parseLong(values.get(8).substring(6)),
                    legacy?Mode.FULL_SEARCH:Mode.valueOf(values.get(9).substring(5)));
        } catch (Exception error) { throw new IllegalStateException("Missing or incompatible Eriksson checkpoint",error); }
    }
}
