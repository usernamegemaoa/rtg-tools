/*
 * Copyright (c) 2014. Real Time Genomics Limited.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rtg.vcf.eval;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.rtg.sam.SamRangeUtils;
import com.rtg.tabix.TabixIndexer;
import com.rtg.tabix.UnindexableDataException;
import com.rtg.util.IORunnable;
import com.rtg.util.Pair;
import com.rtg.util.SimpleThreadPool;
import com.rtg.util.diagnostic.Diagnostic;
import com.rtg.util.intervals.ReferenceRanges;
import com.rtg.util.intervals.RegionRestriction;
import com.rtg.util.io.MemoryPrintStream;
import com.rtg.util.io.TestDirectory;
import com.rtg.util.test.FileHelper;
import com.rtg.vcf.VcfReader;
import com.rtg.vcf.VcfRecord;
import com.rtg.vcf.header.VcfHeader;

import junit.framework.TestCase;

public class SplitEvalSynchronizerTest extends TestCase {
  private static class MockVariantSet implements VariantSet {
    int mSetId = 0;
    final VcfHeader mHeader;
    MockVariantSet() {
      mHeader = new VcfHeader();
      mHeader.addLine(VcfHeader.VERSION_LINE);
      mHeader.addSampleName("SAMPLE");
    }
    @Override
    public Pair<String, Map<VariantSetType, List<Variant>>> nextSet() {
      if (mSetId >= 3) {
        return null;
      }
      mSetId++;
      final HashMap<VariantSetType, List<Variant>> result = new HashMap<>();
      final List<Variant> empty = Collections.emptyList();
      result.put(VariantSetType.CALLS, empty);
      result.put(VariantSetType.BASELINE, empty);
      return new Pair<String, Map<VariantSetType, List<Variant>>>("name" + mSetId, result);
    }

    @Override
    public VcfHeader baseLineHeader() {
      return mHeader;
    }

    @Override
    public VcfHeader calledHeader() {
      return mHeader;
    }

    @Override
    public int getNumberOfSkippedBaselineVariants() {
      return 0;
    }

    @Override
    public int getNumberOfSkippedCalledVariants() {
      return 0;
    }

  }

  private static final String REC1_1 = "name1 3 . A G 0.0 PASS . GT 1/1".replaceAll(" ", "\t");
  private static final String REC1_2 = REC1_1.replaceAll("name1", "name2");
  private static final String REC2_1 = "name1 4 . C G 0.0 PASS . GT 1/1".replaceAll(" ", "\t");
  private static final String REC2_2 = REC2_1.replaceAll("name1", "name2");
  private static final String REC3_1 = "name1 5 . A G 0.0 PASS . GT 1/1".replaceAll(" ", "\t");
  private static final String REC3_2 = REC3_1.replaceAll("name1", "name2");
  private static final String REC4_1 = "name1 6 . C G 0.0 PASS . GT 1/1".replaceAll(" ", "\t");
  private static final String REC4_2 = REC4_1.replaceAll("name1", "name2");
  private static final String REC5_1 = "name1 7 . A G 0.0 PASS . GT 1/1".replaceAll(" ", "\t");
  private static final String REC5_2 = REC5_1.replaceAll("name1", "name2");
  private static final String REC6_1 = "name1 8 . C G 0.0 PASS . GT 1/1".replaceAll(" ", "\t");
  private static final String REC6_2 = REC6_1.replaceAll("name1", "name2");

  private static final String NAME1_RECS = REC1_1 + "\n" + REC2_1 + "\n" + REC3_1 + "\n" + REC4_1 + "\n" + REC5_1 + "\n" + REC6_1 + "\n";
  private static final String NAME2_RECS = REC1_2 + "\n" + REC2_2 + "\n" + REC3_2 + "\n" + REC4_2 + "\n" + REC5_2 + "\n" + REC6_2 + "\n";

  private static final String FAKE_HEADER = VcfHeader.MINIMAL_HEADER + "\tSAMPLE\n";
  private static final String FAKE_VCF = FAKE_HEADER + NAME1_RECS + NAME2_RECS;

  public static OrientedVariant createOrientedVariant(Variant variant, boolean isAlleleA) {
    final OrientedVariant v = OrientedVariantTest.createOrientedVariant(variant, isAlleleA);
    v.setStatus(VariantId.STATUS_GT_MATCH);
    v.setWeight(1.0);
    return v;
  }
  public static Variant createVariant(VcfRecord rec, int id) {
    final Variant v = VariantTest.createVariant(rec, id, 0);
    v.setStatus(VariantId.STATUS_NO_MATCH);
    return v;
  }

  public void testEvalSynchronizer() throws IOException, UnindexableDataException {
    final MemoryPrintStream mp = new MemoryPrintStream();
    Diagnostic.setLogStream(mp.printStream());
    try {
      try (final TestDirectory dir = new TestDirectory()) {
        final File fake = FileHelper.stringToGzFile(FAKE_VCF, new File(dir, "fake.vcf.gz"));
        new TabixIndexer(fake).saveVcfIndex();
        final VcfHeader header = new VcfHeader();
        header.addLine(VcfHeader.VERSION_LINE);
        header.addSampleName("SAMPLE");
        final ReferenceRanges<String> ranges = SamRangeUtils.createExplicitReferenceRange(new RegionRestriction("name1:1-30"), new RegionRestriction("name2:1-30"));
        try (final SplitEvalSynchronizer sync = new SplitEvalSynchronizer(fake, fake, new MockVariantSet(), ranges, null, RocSortValueExtractor.NULL_EXTRACTOR, dir, false, false, false, null)) {
          final Pair<String, Map<VariantSetType, List<Variant>>> pair = sync.nextSet();
          final Pair<String, Map<VariantSetType, List<Variant>>> pair2 = sync.nextSet();
          assertEquals("name1", pair.getA());
          assertEquals("name2", pair2.getA());

          final SimpleThreadPool simpleThreadPool = new SimpleThreadPool(3, "pool", true);
          simpleThreadPool.execute(new IORunnable() {
            @Override
            public void run() throws IOException {
              sync.write("name2",
                Arrays.asList(createVariant(VcfReader.vcfLineToRecord(REC5_2), 5)),
                Arrays.asList(createOrientedVariant(VariantTest.createVariant(VcfReader.vcfLineToRecord(REC1_2), 1, 0), true), createVariant(VcfReader.vcfLineToRecord(REC3_2), 3)), Collections.<Integer>emptyList(), Collections.<Integer>emptyList());
            }
          });
          simpleThreadPool.execute(new IORunnable() {
            @Override
            public void run() throws IOException {
              sync.write("name1",
                Arrays.asList(createVariant(VcfReader.vcfLineToRecord(REC6_1), 6)),
                Arrays.asList(createOrientedVariant(VariantTest.createVariant(VcfReader.vcfLineToRecord(REC2_1), 2, 0), true), createVariant(VcfReader.vcfLineToRecord(REC4_1), 4)), Collections.<Integer>emptyList(), Collections.<Integer>emptyList());
            }
          });
          simpleThreadPool.terminate();

          assertEquals(2, sync.mCallTruePositives);
          assertEquals(2, sync.mFalseNegatives);
          assertEquals(2, sync.mFalsePositives);
          assertEquals("name3", sync.nextSet().getA());
          assertEquals(null, sync.nextSet());
        }
        assertEquals(FAKE_HEADER + REC2_1 + "\n" + REC1_2 + "\n", FileHelper.fileToString(new File(dir, "tp.vcf")));
        assertEquals(FAKE_HEADER + REC4_1 + "\n" + REC3_2 + "\n", FileHelper.fileToString(new File(dir, "fp.vcf")));
        assertEquals(FAKE_HEADER + REC6_1 + "\n" + REC5_2 + "\n", FileHelper.fileToString(new File(dir, "fn.vcf")));
      }
    } finally {
      Diagnostic.setLogStream();
    }
  }

  public void testAbort() throws IOException, UnindexableDataException {
    try (final TestDirectory dir = new TestDirectory()) {
      final File fake = FileHelper.stringToGzFile(FAKE_VCF, new File(dir, "fake.vcf.gz"));
      new TabixIndexer(fake).saveVcfIndex();
      final ReferenceRanges<String> ranges = SamRangeUtils.createExplicitReferenceRange(new RegionRestriction("name1:1-30"), new RegionRestriction("name2:1-30"));
      try (final SplitEvalSynchronizer sync = new SplitEvalSynchronizer(fake, fake, new MockVariantSet(), ranges, null, RocSortValueExtractor.NULL_EXTRACTOR, dir, false, false, false, null)) {
        final Pair<String, Map<VariantSetType, List<Variant>>> pair = sync.nextSet();
        final Pair<String, Map<VariantSetType, List<Variant>>> pair2 = sync.nextSet();
        assertEquals("name1", pair.getA());
        assertEquals("name2", pair2.getA());

        final SimpleThreadPool simpleThreadPool = new SimpleThreadPool(3, "pool", true);
        simpleThreadPool.execute(new IORunnable() {
          @Override
          public void run() throws IOException {
            sync.write("name2", null, Arrays.asList(OrientedVariantTest.createOrientedVariant(VariantTest.createVariant(VcfReader.vcfLineToRecord(REC1_2), 0), true)), Collections.<Integer>emptyList(), Collections.<Integer>emptyList());
            fail("Should have aborted in thread");
          }
        });
        simpleThreadPool.execute(new IORunnable() {
          @Override
          public void run() throws IOException {
            throw new IOException();
          }
        });
        try {
          simpleThreadPool.terminate();
        } catch (final IOException e) {

        }
      }
    }
  }


  public void testInterrupt() throws IOException, InterruptedException, UnindexableDataException {
    try (final TestDirectory dir = new TestDirectory()) {
      final File fake = FileHelper.stringToGzFile(FAKE_VCF, new File(dir, "fake.vcf.gz"));
      new TabixIndexer(fake).saveVcfIndex();
      final ReferenceRanges<String> ranges = SamRangeUtils.createExplicitReferenceRange(new RegionRestriction("name1:1-30"), new RegionRestriction("name2:1-30"));
      try (final SplitEvalSynchronizer sync = new SplitEvalSynchronizer(fake, fake, new MockVariantSet(), ranges, null, RocSortValueExtractor.NULL_EXTRACTOR, dir, false, false, false, null)) {
        final Pair<String, Map<VariantSetType, List<Variant>>> pair = sync.nextSet();
        final Pair<String, Map<VariantSetType, List<Variant>>> pair2 = sync.nextSet();
        assertEquals("name1", pair.getA());
        assertEquals("name2", pair2.getA());

        final Exception[] internalException = new Exception[1];
        final Thread t = new Thread() {
          @Override
          public void run() {
            try {
              sync.write("name2", null, Arrays.asList(OrientedVariantTest.createOrientedVariant(VariantTest.createVariant(VcfReader.vcfLineToRecord(REC1_2), 0), true)), Collections.<Integer>emptyList(), Collections.<Integer>emptyList());
            } catch (final IllegalStateException e) {
              internalException[0] = e;
            } catch (final IOException e) {
            }
          }
        };
        t.start();
        t.interrupt();
        t.join();
        assertEquals("Interrupted. Unexpectedly", internalException[0].getMessage());
      }
    }
  }
}
