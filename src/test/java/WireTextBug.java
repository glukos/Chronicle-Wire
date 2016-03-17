import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.wire.BinaryWire;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireKey;

/**
 */
public class WireTextBug {

    @org.junit.Test
    public void testText() throws Exception {
        ClassAliasPool.CLASS_ALIASES.addAlias(Bug.class);
        Wire encodeWire = new BinaryWire(Bytes.elasticByteBuffer().unchecked(true), false, true, false, Integer.MAX_VALUE, "lzw");
        Bug b = new Bug();
        b.setClOrdID("FIX.4.4:12345678_client1->FOO/MINI1-1234567891234-12");
        System.out.println("b = " + b);
        encodeWire.getValueOut().object(b);
        byte[] bytes = encodeWire.bytes().toByteArray();

        Wire decodeWire = new BinaryWire(Bytes.wrapForRead(bytes).unchecked(true));
        Object o = decodeWire.getValueIn().object(Object.class);
        Bug b2 = (Bug) o;
        System.out.println("b2 = " + b2);
    }

    public enum FIXTag implements WireKey {
        ClOrdID(11);
        private final int mCode;
        private final String mString;

        FIXTag(int aCode) {
            mCode = aCode;
            mString = mCode + "";
        }

        @Override
        public int code() {
            return mCode;
        }

        @Override
        public boolean contentEquals(CharSequence c) {
            return mString.contentEquals(c) || name().equals(c);
        }

        @Override
        public String toString() {
            return mString;
        }
    }

    class Bug implements Marshallable {
        private String clOrdID;

        public String getClOrdID() {
            return clOrdID;
        }

        public void setClOrdID(String aClOrdID) {
            clOrdID = aClOrdID;
        }

        @Override
        public boolean equals(Object o) {
            return Marshallable.$equals(this, o);
        }

        @Override
        public int hashCode() {
            return Marshallable.$hashCode(this);
        }

        @Override
        public String toString() {
            return Marshallable.$toString(this);
        }
    }
}