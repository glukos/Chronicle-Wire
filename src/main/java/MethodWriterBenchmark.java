package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.jlbh.JLBH;
import net.openhft.chronicle.core.jlbh.JLBHOptions;
import net.openhft.chronicle.core.jlbh.JLBHTask;

import java.nio.ByteBuffer;

public final class MethodWriterBenchmark implements JLBHTask {

    private Printer printer;
    private JLBH jlbh;
    private Bytes<ByteBuffer> bytes;

    interface Printer {
        void print(String message);
    }

    @Override
    public void run(long startTimeNS) {

        bytes.clear();
        printer.print("hello world");
        jlbh.sample(System.nanoTime() - startTimeNS);
    }


    @Override
    public void init(JLBH jlbh) {
      //  System.setProperty("disableProxyCodegen","true");
        bytes = Bytes.elasticByteBuffer();
        Wire wire = WireType.BINARY.apply(bytes);
        printer = wire.methodWriterBuilder(Printer.class).build();
        this.jlbh = jlbh;
    }

    @Override
    public void complete() {

    }

    public static void main(String[] args) {
        final JLBHOptions lth = new JLBHOptions()
                .warmUpIterations(50000)
                .iterations(1000_000)
                .throughput(100_000)
                .recordOSJitter(false)
                // disable as otherwise single GC event skews results heavily
                .accountForCoordinatedOmmission(false)
                .skipFirstRun(true)
                .runs(5)
                .jlbhTask(new MethodWriterBenchmark());
        new JLBH(lth).start();
    }
}
