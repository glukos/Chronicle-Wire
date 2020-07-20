package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.threads.NamedThreadFactory;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.BufferUnderflowException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public final class BinaryWireStringInternerTest extends WireTestCommon {
    private static final int DATA_SET_SIZE = 2_000;

    private final String[] testData = new String[DATA_SET_SIZE];
    private final String[] internedStrings = new String[DATA_SET_SIZE];
    @SuppressWarnings("rawtypes")
    private final Bytes heapBytes = Bytes.allocateElasticOnHeap(4096);
    private final BinaryWire wire = BinaryWire.binaryOnly(heapBytes);

    private static String message(final int index, final String inputData) {
        return String.format("At index %d for string %s",
                index, inputData);
    }

    private static String makeString(final int length, final Random random) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            builder.append((char) ('a' + random.nextInt('z' - 'a')));
        }
        return builder.toString();
    }

    @NotNull
    public static String generateText(int i, int j) {
        return "test-" + i + "-" + j;
    }

    @NotNull
    public static String generateText(int i) {
        return "test-" + i / 10 + "-" + i % 10;
    }

    @Before
    public void createTestData() throws Exception {
        for (int i = 0; i < DATA_SET_SIZE; i++) {
            testData[i] = generateText(i);
        }

        for (int i = 0; i < DATA_SET_SIZE; i++) {
            wire.getFixedBinaryValueOut(true).text(testData[i]);
            internedStrings[i] = wire.read().text();
        }
        wire.clear();
    }

    @Test
    public void shouldInternExistingStringsAlright() throws Exception {
        final List<Throwable> capturedExceptions = new CopyOnWriteArrayList<>();

        final ExecutorService executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                new NamedThreadFactory("test"));

        int tasks = Jvm.isArm() ? 100 : 1000;
        for (int i = 0; i < tasks; i++) {
            executorService.submit(new BinaryTextReaderWriter(capturedExceptions::add,
                    () -> BinaryWire.binaryOnly(Bytes.allocateElasticOnHeap(4096))));
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < tasks * 100; i++) {
            wire.clear();
            final int dataPointIndex = random.nextInt(DATA_SET_SIZE);
            wire.getFixedBinaryValueOut(true).text(testData[dataPointIndex]);

            final String inputData = wire.getValueIn().text();
            assertEquals(message(i, inputData), internedStrings[dataPointIndex], inputData);
        }

        executorService.shutdown();
        assertTrue("jobs did not complete in time", executorService.awaitTermination(15L, TimeUnit.SECONDS));
        assertTrue(capturedExceptions.isEmpty());
    }

    @Test
    public void multipleThreadsUsingBinaryWiresShouldNotCauseProblems() throws Exception {
        // TODO FIX
        assumeFalse(Jvm.isArm());
        final List<Throwable> capturedExceptions = new CopyOnWriteArrayList<>();

        final ExecutorService executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                new NamedThreadFactory("multipleThreadsUsingBinaryWiresShouldNotCauseProblems"));

        int tasks = Jvm.isArm() ? 100 : 1000;
        for (int i = 0; i < tasks; i++) {
            executorService.submit(
                    new BinaryTextReaderWriter(capturedExceptions::add,
                            () -> BinaryWire.binaryOnly(
                                    Bytes.allocateElasticOnHeap(4096))));
        }

        executorService.shutdown();
        long timeout = Jvm.isArm() ? 15 : 5;
        assertTrue("jobs did not complete in time",
                executorService.awaitTermination(timeout, TimeUnit.SECONDS));
        assertTrue(capturedExceptions.isEmpty());
    }

    @Ignore("used to demonstrate errors that can occur when buffers are shared between threads")
    @Test
    public void multipleThreadsSharingBinaryWireShouldCauseProblems() throws Exception {
        final List<Throwable> capturedExceptions = new CopyOnWriteArrayList<>();

        final ExecutorService executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                new NamedThreadFactory("multipleThreadsSharingBinaryWireShouldCauseProblems"));

        final BinaryWire sharedMutableWire = BinaryWire.binaryOnly(Bytes.allocateElasticOnHeap(4096));
        for (int i = 0; i < 50; i++) {
            executorService.submit(new BinaryTextReaderWriter(capturedExceptions::add, () -> sharedMutableWire));
            if (!capturedExceptions.isEmpty()) {
                break;
            }
            Jvm.pause(1);
        }

        executorService.shutdown();
        assertTrue("jobs did not complete in time", executorService.awaitTermination(15L, TimeUnit.SECONDS));
        capturedExceptions.stream()
                .filter(e -> e instanceof BufferUnderflowException)
                .limit(2)
                .forEach(Throwable::printStackTrace);
        assertTrue(capturedExceptions.isEmpty());
    }

    private static final class BinaryTextReaderWriter implements Runnable {
        private final ThreadLocal<BinaryWire> wire;
        private final Consumer<Throwable> exceptionConsumer;

        private BinaryTextReaderWriter(final Consumer<Throwable> exceptionConsumer,
                                       final Supplier<BinaryWire> binaryWireSupplier) {
            this.exceptionConsumer = exceptionConsumer;
            wire = ThreadLocal.withInitial(binaryWireSupplier);
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < DATA_SET_SIZE / 10; i++) {
                    BinaryWire binaryWire = wire.get();
                    binaryWire.clear();
                    for (int j = 0; j < 10; j++) {
                        binaryWire.getFixedBinaryValueOut(true)
                                .text(generateText(i, j));

                        if (binaryWire.read().text() == null) {
                            exceptionConsumer.accept(new IllegalStateException("text was null"));
                        }
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
                exceptionConsumer.accept(e);
            }
        }
    }
}