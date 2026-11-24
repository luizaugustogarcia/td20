package br.unb.cic.td20.certificate;

import br.unb.cic.td20.compat.LegacyCertificates;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import lombok.val;
import org.rocksdb.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Reads and replays complete witnesses from an independently supplied database.
 */
public final class ReadOnlyWitnessSource implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions options = new DBOptions();
    private final List<ColumnFamilyOptions> familyOptions = new ArrayList<>();
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses;

    public ReadOnlyWitnessSource(final Path directory) {
        RocksDB opened = null;

        try (val listing = new Options()) {
            val families = RocksDB.listColumnFamilies(listing, directory.toAbsolutePath().toString());
            val descriptors = new ArrayList<ColumnFamilyDescriptor>();
            int defaultIndex = -1;
            for (int i = 0; i < families.size(); i++) {
                val family = new ColumnFamilyOptions();
                familyOptions.add(family);
                descriptors.add(new ColumnFamilyDescriptor(families.get(i), family));
                if (Arrays.equals(families.get(i), RocksDB.DEFAULT_COLUMN_FAMILY)) {
                    defaultIndex = i;
                }
            }
            if (defaultIndex < 0) {
                throw new IllegalStateException("Witness source has no default column family");
            }

            opened = RocksDB.openReadOnly(options, directory.toAbsolutePath().toString(), descriptors, handles);
            database = opened;
            witnesses = handles.get(defaultIndex);
        } catch (final RocksDBException | RuntimeException failure) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) {
                opened.close();
            }

            familyOptions.forEach(ColumnFamilyOptions::close);
            options.close();
            throw new IllegalStateException("Cannot open witness source " + directory, failure);
        }
    }

    public Optional<List<Cycle>> find(final CyclicTargetPair pair, final int maximumMoves) {
        return findPrimitive(LegacyPairs.fromLegacy(pair), maximumMoves).map(result -> LegacyCertificates.toLegacy(result).moves());
    }

    public Optional<PrimitiveCertificate> findPrimitive(final PrimitivePair pair, final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > 11) {
            throw new IllegalArgumentException("Budget must be 1..11");
        }

        try {
            return checkedResult(pair, maximumMoves, database.get(witnesses, CertificateCodec.keyFor(pair)));
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot read witness source", failure);
        }
    }

    /**
     * Position-preserving native bulk lookup of independently supplied complete witnesses.
     */
    public List<Optional<PrimitiveCertificate>> findPrimitives(final List<PrimitivePair> pairs, final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > 11) {
            throw new IllegalArgumentException("Budget must be 1..11");
        }
        if (pairs.isEmpty()) {
            return List.of();
        }

        val keys = pairs.stream().map(CertificateCodec::keyFor).toList();

        try {
            val encoded = database.multiGetAsList(Collections.nCopies(keys.size(), witnesses), keys);
            if (encoded.size() != pairs.size()) {
                throw new IllegalStateException("Incomplete witness-source multiGet response");
            }

            val results = new ArrayList<Optional<PrimitiveCertificate>>(pairs.size());
            for (int i = 0; i < pairs.size(); i++) {
                results.add(checkedResult(pairs.get(i), maximumMoves, encoded.get(i)));
            }

            return List.copyOf(results);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot bulk-read witness source", failure);
        }
    }

    private static Optional<PrimitiveCertificate> checkedResult(final PrimitivePair pair, final int maximumMoves, final byte[] encoded) {
        if (encoded == null) {
            return Optional.empty();
        }

        val result = CertificateCodec.decodePrimitiveValue(encoded);
        CertificateValidator.validate(pair, result, result.moveCount());

        return result.moveCount() <= maximumMoves ? Optional.of(result) : Optional.empty();
    }

    @Override
    public void close() {
        handles.forEach(ColumnFamilyHandle::close);
        database.close();
        familyOptions.forEach(ColumnFamilyOptions::close);
        options.close();
    }
}
