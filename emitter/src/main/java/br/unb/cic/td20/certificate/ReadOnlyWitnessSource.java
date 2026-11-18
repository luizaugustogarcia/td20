package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.compat.LegacyCertificates;
import br.unb.cic.td20.permutation.Cycle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        return findPrimitive(LegacyPairs.fromLegacy(pair), maximumMoves)
                .map(result -> LegacyCertificates.toLegacy(result).moves());
    }

    public Optional<PrimitiveCertificate> findPrimitive(PrimitivePair pair, int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 1..11");
        try {
            return checkedResult(pair, maximumMoves, database.get(witnesses, CertificateCodec.keyFor(pair)));
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot read witness source", failure); }
    }

    /** Position-preserving native bulk lookup of independently supplied complete witnesses. */
    public List<Optional<PrimitiveCertificate>> findPrimitives(List<PrimitivePair> pairs, int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 1..11");
        if (pairs.isEmpty()) return List.of();
        var keys = pairs.stream().map(CertificateCodec::keyFor).toList();
        try {
            var encoded = database.multiGetAsList(Collections.nCopies(keys.size(), witnesses), keys);
            if (encoded.size() != pairs.size()) throw new IllegalStateException("Incomplete witness-source multiGet response");
            var results = new ArrayList<Optional<PrimitiveCertificate>>(pairs.size());
            for (int i = 0; i < pairs.size(); i++) results.add(checkedResult(pairs.get(i), maximumMoves, encoded.get(i)));
            return List.copyOf(results);
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot bulk-read witness source", failure); }
    }

    private static Optional<PrimitiveCertificate> checkedResult(PrimitivePair pair, int maximumMoves, byte[] encoded) {
        if (encoded == null) return Optional.empty();
        var result = CertificateCodec.decodePrimitiveValue(encoded);
        CertificateValidator.validate(pair, result, result.moveCount());
        return result.moveCount() <= maximumMoves ? Optional.of(result) : Optional.empty();
    }

    @Override public void close() {
        handles.forEach(ColumnFamilyHandle::close);
        database.close();
        familyOptions.forEach(ColumnFamilyOptions::close);
        options.close();
    }
}
