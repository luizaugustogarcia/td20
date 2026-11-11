package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.rocksdb.*;

/** Reads and replays complete witnesses from an independently supplied database. */
public final class ReadOnlyWitnessSource implements AutoCloseable {
    static { RocksDB.loadLibrary(); }
    private final DBOptions options = new DBOptions();
    private final List<ColumnFamilyOptions> familyOptions = new ArrayList<>();
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses;

    public ReadOnlyWitnessSource(Path directory) {
        RocksDB opened = null;
        try (var listing = new Options()) {
            var families = RocksDB.listColumnFamilies(listing, directory.toAbsolutePath().toString());
            var descriptors = new ArrayList<ColumnFamilyDescriptor>();
            int defaultIndex = -1;
            for (int i = 0; i < families.size(); i++) {
                var family = new ColumnFamilyOptions();
                familyOptions.add(family);
                descriptors.add(new ColumnFamilyDescriptor(families.get(i), family));
                if (Arrays.equals(families.get(i), RocksDB.DEFAULT_COLUMN_FAMILY)) defaultIndex = i;
            }
            if (defaultIndex < 0) throw new IllegalStateException("Witness source has no default column family");
            opened = RocksDB.openReadOnly(options, directory.toAbsolutePath().toString(), descriptors, handles);
            database = opened;
            witnesses = handles.get(defaultIndex);
        } catch (RocksDBException | RuntimeException failure) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            familyOptions.forEach(ColumnFamilyOptions::close);
            options.close();
            throw new IllegalStateException("Cannot open witness source " + directory, failure);
        }
    }

    public Optional<List<Cycle>> find(CyclicTargetPair pair, int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 1..11");
        try {
            byte[] encoded = database.get(witnesses, CertificateCodec.keyFor(pair));
            if (encoded == null) return Optional.empty();
            var moves = CertificateCodec.decodeValue(encoded);
            CertificateValidator.validate(pair, moves, moves.size());
            return moves.size() <= maximumMoves ? Optional.of(moves) : Optional.empty();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot read witness source", failure); }
    }

    @Override public void close() {
        handles.forEach(ColumnFamilyHandle::close);
        database.close();
        familyOptions.forEach(ColumnFamilyOptions::close);
        options.close();
    }
}
