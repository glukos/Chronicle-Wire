package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.Byteable;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesUtil;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.Threads;
import net.openhft.chronicle.core.pool.StringInterner;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.util.BooleanConsumer;
import net.openhft.chronicle.util.ByteConsumer;
import net.openhft.chronicle.util.FloatConsumer;
import net.openhft.chronicle.util.ShortConsumer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.function.*;

import static net.openhft.chronicle.wire.WireType.Codes.*;
import static net.openhft.chronicle.wire.WireType.stringForCode;

/**
 * Created by peter.lawrey on 15/01/15.
 */
public class BinaryWire implements Wire {
    static final int NOT_READY = 1 << 31;
    static final int META_DATA = 1 << 30;
    public static final int ANY_CODE_MATCH = -1;
    static final int UNKNOWN_LENGTH = -1 >>> 2;
    static final int LENGTH_MASK = -1 >>> 2;

    final Bytes<?> bytes;
    final ValueOut fixedValueOut = new FixedBinaryValueOut();
    final ValueOut valueOut;
    final ValueIn valueIn = new BinaryValueIn();
    private final boolean numericFields;
    private final boolean fieldLess;

    public BinaryWire(Bytes<?> bytes) {
        this(bytes, false, false, false);
    }

    public BinaryWire(Bytes<?> bytes, boolean fixed, boolean numericFields, boolean fieldLess) {
        this.numericFields = numericFields;
        this.fieldLess = fieldLess;
        this.bytes = bytes;
        valueOut = fixed ? fixedValueOut : new BinaryValueOut();
    }

    public static boolean isReady(int len) {
        return (len & NOT_READY) == 0;
    }

    public static boolean isDocument(int len) {
        return (len & META_DATA) == 0;
    }

    public static boolean isKnownLength(int len) {
        return (len & LENGTH_MASK) != UNKNOWN_LENGTH;
    }

    public static int toIntU30(long l, String error) {
        if (l < 0 || l >= UNKNOWN_LENGTH)
            throw new IllegalStateException(String.format(error, l));
        return (int) l;
    }

    @Override
    public Bytes<?> bytes() {
        return bytes;
    }

    @Override
    public void copyTo(WireOut wire) {
        while (bytes.remaining() > 0) {
            int peekCode = peekCode();
            outerSwitch:
            switch (peekCode >> 4) {
                case NUM0:
                case NUM1:
                case NUM2:
                case NUM3:
                case NUM4:
                case NUM5:
                case NUM6:
                case NUM7:
                    bytes.skip(1);
                    wire.writeValue().uint8(peekCode);
                    break;

                case CONTROL:
                    switch (peekCode) {
                        case PADDING:
                            bytes.skip(1);
                            break outerSwitch;
                        case PADDING32:
                            bytes.skip(1);
                            bytes.skip(bytes.readUnsignedInt());
                            break outerSwitch;
                        default:
                            throw new UnsupportedOperationException();
                    }

                case FLOAT:
                    bytes.skip(1);
                    double d = readFloat0(peekCode);
                    wire.writeValue().float64(d);
                    break;

                case INT:
                    bytes.skip(1);
                    long l = readInt0(peekCode);
                    wire.writeValue().int64(l);
                    break;

                case SPECIAL:
                    copySpecial(wire, peekCode);
                    break;

                case FIELD0:
                case FIELD1:
                    StringBuilder fsb = readField(peekCode, ANY_CODE_MATCH, Wires.acquireStringBuilder());
                    wire.write(() -> fsb);
                    break;

                case STR0:
                case STR1:
                    bytes.skip(1);
                    StringBuilder sb = readText(peekCode, Wires.acquireStringBuilder());
                    wire.writeValue().text(sb);
                    break;

            }
        }
    }

    private void copySpecial(WireOut wire, int peekCode) {
        switch (peekCode) {
            case COMMENT:
            {
                bytes.skip(1);
                StringBuilder sb = Wires.acquireStringBuilder();
                bytes.readUTFΔ(sb);
                wire.writeComment(sb);
                break;
            }
            case HINT:
            {
                bytes.skip(1);
                StringBuilder sb = Wires.acquireStringBuilder();
                bytes.readUTFΔ(sb);
                break;
            }
            case TIME:
            case ZONED_DATE_TIME:
            case DATE:
                throw new UnsupportedOperationException();
            case TYPE:
            {
                bytes.skip(1);
                StringBuilder sb = Wires.acquireStringBuilder();
                bytes.readUTFΔ(sb);
                wire.writeValue().type(sb);
                break;
            }
            case FIELD_NAME_ANY:
                StringBuilder fsb = readField(peekCode, ANY_CODE_MATCH, Wires.acquireStringBuilder());
                wire.write(() -> fsb);
                break;
            case STRING_ANY:
                bytes.skip(1);
                StringBuilder sb = readText(peekCode, Wires.acquireStringBuilder());
                wire.writeValue().text(sb);
                break;
            case FIELD_NUMBER: {
                bytes.skip(1);
                long code2 = bytes.readStopBit();
                wire.write(new WireKey() {
                    @Override
                    public String name() {
                        return null;
                    }

                    @Override
                    public int code() {
                        return (int) code2;
                    }
                });
                break;
            }

            // Boolean
            case NULL:
                bytes.skip(1);
                wire.writeValue().bool(null);
                break;
            case FALSE:
                bytes.skip(1);
                wire.writeValue().bool(false);
                break;
            case TRUE:
                bytes.skip(1);
                wire.writeValue().bool(true);
                break;
            default:
                throw new UnsupportedOperationException(WireType.stringForCode(peekCode));
        }
    }

    private void consumeSpecial() {
        while (true) {
            int code = peekCode();
            switch (code) {
                case PADDING:
                    bytes.skip(1);
                    break;
                case PADDING32:
                    bytes.skip(1);
                    bytes.skip(bytes.readUnsignedInt());
                    break;
                case COMMENT:
                {
                    bytes.skip(1);
                    StringBuilder sb = Wires.acquireStringBuilder();
                    bytes.readUTFΔ(sb);
                    break;
                }
                case HINT:
                {
                    bytes.skip(1);
                    StringBuilder sb = Wires.acquireStringBuilder();
                    bytes.readUTFΔ(sb);
                    break;
                }
                default:
                    return;
            }
        }
    }

    long readInt(int code) {
        if (code < 128)
            return code;
        switch (code >> 4) {
            case SPECIAL:
                switch (code) {
                    case FALSE:
                        return 0;
                    case TRUE:
                        return 1;
                }
                break;
            case FLOAT:
                double d = readFloat0(code);
                return (long) d;

            case INT:
                return readInt0(code);

        }
        throw new UnsupportedOperationException(WireType.stringForCode(code));
    }

    private double readFloat(int code) {
        if (code < 128)
            return code;
        switch (code >> 4) {
            case FLOAT:
                return readFloat0(code);

            case INT:
                return readInt0(code);

        }
        throw new UnsupportedOperationException(WireType.stringForCode(code));
    }

    private long readInt0(int code) {
        switch (code) {
            case UUID: //TODO !!! was FIELD_NUMBER(0xA0), FIELD_NUMBER = 0xB9, UUID = 0xA0
                throw new UnsupportedOperationException();
            case UTF8:
                throw new UnsupportedOperationException();
            case INT8:
                return bytes.readByte();
            case INT16:
                return bytes.readShort();
            case INT32:
                return bytes.readInt();
            case INT64:
                return bytes.readLong();
            case UINT8:
                return bytes.readUnsignedByte();
            case UINT16:
                return bytes.readUnsignedShort();
            case UINT32:
                return bytes.readUnsignedInt();
            case FIXED_6:
                return bytes.readStopBit() * 1000000L;
            case FIXED_5:
                return bytes.readStopBit() * 100000L;
            case FIXED_4:
                return bytes.readStopBit() * 10000L;
            case FIXED_3:
                return bytes.readStopBit() * 1000L;
            case FIXED_2:
                return bytes.readStopBit() * 100L;
            case FIXED_1:
                return bytes.readStopBit() * 10L;
            case FIXED:
                return bytes.readStopBit();

        }
        throw new UnsupportedOperationException(WireType.stringForCode(code));
    }

    private double readFloat0(int code) {
        switch (code) {
            case FLOAT32:
                return bytes.readFloat();
            case FLOAT64:
                return bytes.readDouble();
            case FIXED1:
                return bytes.readStopBit() / 1e1;
            case FIXED2:
                return bytes.readStopBit() / 1e2;
            case FIXED3:
                return bytes.readStopBit() / 1e3;
            case FIXED4:
                return bytes.readStopBit() / 1e4;
            case FIXED5:
                return bytes.readStopBit() / 1e5;
            case FIXED6:
                return bytes.readStopBit() / 1e6;
        }
        throw new UnsupportedOperationException(WireType.stringForCode(code));
    }

    @Override
    public ValueOut write() {
        if (!fieldLess) {
            writeField("");
        }
        return valueOut;
    }

    @Override
    public ValueOut writeValue() {
        return valueOut;
    }

    @Override
    public ValueOut write(WireKey key) {
        if (!fieldLess) {
            if (numericFields)
                writeField(key.code());
            else
                writeField(key.name());
        }
        return valueOut;
    }

    private void writeField(int code) {
        writeCode(FIELD_NUMBER);
        bytes.writeStopBit(code);
    }

    private void writeField(CharSequence name) {
        int len = name.length();
        if (len < 0x20) {
            if (len > 0 && Character.isDigit(name.charAt(0))) {
                try {
                    writeField(Integer.parseInt(name.toString()));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
            bytes.writeByte((byte) (FIELD_NAME0 + len))
                    .append(name);
        } else {
            writeCode(FIELD_NAME_ANY).writeUTFΔ(name);
        }
    }

    private Bytes writeCode(int code) {
        return bytes.writeByte((byte) code);
    }

    @Override
    public ValueIn read() {
        readField(Wires.acquireStringBuilder(), ANY_CODE_MATCH);
        return valueIn;
    }

    @Override
    public ValueIn read(WireKey key) {
        StringBuilder sb = readField(Wires.acquireStringBuilder(), key.code());
        if (fieldLess || (sb != null && (sb.length() == 0 || StringInterner.isEqual(sb, key.name()))))
            return valueIn;
        throw new UnsupportedOperationException("Unordered fields not supported yet.");
    }

    @Override
    public ValueIn read(StringBuilder name) {
        readField(name, ANY_CODE_MATCH);
        return valueIn;
    }

    private StringBuilder readField(StringBuilder name, int codeMatch) {
        consumeSpecial();
        int peekCode = peekCode();
        return readField(peekCode, codeMatch, name);
    }

    private StringBuilder readField(int peekCode, int codeMatch, StringBuilder sb) {
        switch (peekCode >> 4) {
            case SPECIAL:
                if (peekCode == FIELD_NAME_ANY) {
                    bytes.skip(1);
                    bytes.readUTFΔ(sb);
                    return sb;
                }
                if (peekCode == FIELD_NUMBER) {
                    bytes.skip(1);
                    long fieldId = bytes.readStopBit();
                    if (codeMatch >= 0 && fieldId != codeMatch)
                        throw new UnsupportedOperationException("Field was: " + fieldId + " expected " + codeMatch);
                    if (codeMatch < 0)
                        sb.append(fieldId);
                    return sb;
                }
                return null;
            case FIELD0:
            case FIELD1:
                bytes.skip(1);
                return getStringBuilder(peekCode, sb);
            default:
                break;
        }
        // if field-less accept anything in order.
        if (fieldLess) {
            sb.setLength(0);
            return sb;
        }

        return null;
    }

    StringBuilder readText(int code, StringBuilder sb) {
        switch (code >> 4) {
            case SPECIAL:
                if (code == STRING_ANY) {
                    bytes.readUTFΔ(sb);
                    return sb;
                }
                return null;
            case STR0:
            case STR1:
                return getStringBuilder(code, sb);
            default:
                throw new UnsupportedOperationException();
        }
    }

    private int peekCode() {
        if (bytes.remaining() < 1)
            return END_OF_BYTES;
        long pos = bytes.position();
        return bytes.readUnsignedByte(pos);
    }

    private int readCode() {
        if (bytes.remaining() < 1)
            return END_OF_BYTES;
        return bytes.readUnsignedByte();
    }

    private StringBuilder getStringBuilder(int code, StringBuilder sb) {
        sb.setLength(0);
        BytesUtil.parseUTF(bytes, sb, code & 0x1f);
        return sb;
    }

    @Override
    public boolean hasNextSequenceItem() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasMapping() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasDocument() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void flip() {
        bytes.flip();
    }

    @Override
    public void clear() {
        bytes.clear();
    }

    public String toString() {
        return bytes.toDebugString(bytes.capacity());
    }

    @Override
    public Wire writeComment(CharSequence s) {
        writeCode(COMMENT);
        bytes.writeUTFΔ(s);
        return BinaryWire.this;
    }

    @Override
    public Wire readComment(StringBuilder s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WireOut addPadding(int paddingToAdd) {
        if (paddingToAdd < 0)
            throw new IllegalStateException("Cannot add " + paddingToAdd + " bytes of padding");
        if (paddingToAdd >= 5) {
            writeCode(PADDING32)
                    .writeUnsignedInt(paddingToAdd - 5)
                    .skip(paddingToAdd - 5);
        } else {
            for (int i = 0; i < paddingToAdd; i++)
                writeCode(PADDING);
        }
        return this;
    }

    @Override
    public <T> T readDocument(Function<WireIn, T> reader, Consumer<WireIn> metaDataReader) {
        int length;
        for (; ; ) {
            length = bytes.peakVolatileInt();
            if (length == 0)
                return null;
            if (isDocument(length)) {
                if (reader == null)
                    throw new IllegalStateException("Expected meta data but found a document of length " + length30(length));
                if (isReady(length))
                    return readDocument(reader, length);
                // try again.
            } else {
                if (metaDataReader == null) {
                    // no need to wait for meta data
                    if (isKnownLength(length)) {
                        int length2 = length30(length) + 4;
                        if (bytes.remaining() < length2)
                            throw new IllegalStateException();
                        bytes.skip(length2);
                    }
                    // try again.
                } else if (isReady(length)) {
                    readMetaData(metaDataReader, length);
                    // if we are only looking for meta data, stop after one.
                    if (reader == null)
                        return null;
                    // try again.
                }
            }
            Threads.checkInterrupted();
        }
    }

    private <T> T readDocument(Function<WireIn, T> reader, int length) {
        // consume the length
        bytes.readInt();
        long limit = bytes.readLimit();
        bytes.limit(bytes.position() + length30(length));
        try {
            return reader.apply(this);
        } finally {
            bytes.limit(limit);
        }
    }

    private void readMetaData(Consumer<WireIn> metaDataReader, int length) {
        // consume the length
        bytes.readInt();
        long limit = bytes.readLimit();
        long limit2 = bytes.position() + length30(length);
        bytes.limit(limit2);
        try {
            metaDataReader.accept(this);
        } finally {
            bytes.position(limit2);
            bytes.limit(limit);
        }
    }

    private int length30(int length) {
        return length & LENGTH_MASK;
    }

    @Override
    public void writeDocument(Runnable writer) {
        long position = bytes.position();
        bytes.writeInt(NOT_READY | UNKNOWN_LENGTH);
        writer.run();
        int length = toIntU30(bytes.position() - position - 4, "Document length %,d out of 30-bit int range.");
        bytes.writeOrderedInt(position, length);
    }

    @Override
    public void writeMetaData(Runnable writer) {
        long position = bytes.position();
        bytes.writeInt(NOT_READY | META_DATA | UNKNOWN_LENGTH);
        writer.run();
        int length = META_DATA | toIntU30(bytes.position() - position - 4, "Document length %,d out of 30-bit int range.");
        bytes.writeOrderedInt(position, length);
    }

    class FixedBinaryValueOut implements ValueOut {
        @Override
        public WireOut marshallable(Marshallable object) {
            writeCode(BYTES_LENGTH32);
            long position = bytes.position();
            bytes.writeInt(0);
            object.writeMarshallable(BinaryWire.this);
            bytes.writeOrderedInt(position, Maths.toInt32(bytes.position() - position - 4, "Document length %,d out of 32-bit int range."));
            return BinaryWire.this;
        }

        @Override
        public WireOut text(CharSequence s) {
            if (s == null) {
                writeCode(NULL);
            } else {
                int len = s.length();
                if (len < 0x20) {
                    bytes.writeByte((byte) (STRING0 + len)).append(s);
                } else {
                    writeCode(STRING_ANY).writeUTFΔ(s);
                }
            }

            return BinaryWire.this;
        }

        @Override
        public WireOut type(CharSequence typeName) {
            writeCode(TYPE).writeUTFΔ(typeName);
            return BinaryWire.this;
        }

        @Override
        public WireOut bool(Boolean flag) {
            bytes.writeUnsignedByte(flag == null
                    ? NULL
                    : (flag ? TRUE : FALSE));
            return BinaryWire.this;
        }

        @Override
        public WireOut int8(byte i8) {
            writeCode(INT8).writeByte(i8);
            return BinaryWire.this;
        }

        @Override
        public WireOut bytes(Bytes fromBytes) {
            writeLength(Maths.toInt32(fromBytes.remaining() + 1));
            writeCode(U8_ARRAY);
            bytes.write(fromBytes);
            return BinaryWire.this;
        }

        @Override
        public WireOut bytes(byte[] fromBytes) {
            writeLength(Maths.toInt32(fromBytes.length + 1));
            writeCode(U8_ARRAY);
            bytes.write(fromBytes);
            return BinaryWire.this;
        }

        @Override
        public WireOut uint8checked(int u8) {
            writeCode(UINT8).writeUnsignedByte(u8);
            return BinaryWire.this;
        }

        @Override
        public WireOut int16(short i16) {
            writeCode(INT16).writeShort(i16);
            return BinaryWire.this;
        }

        @Override
        public WireOut uint16checked(int u16) {
            writeCode(UINT16).writeUnsignedShort(u16);
            return BinaryWire.this;
        }

        @Override
        public WireOut utf8(int codepoint) {
            writeCode(UINT16);
            BytesUtil.appendUTF(bytes, codepoint);
            return BinaryWire.this;
        }

        @Override
        public WireOut int32(int i32) {
            writeCode(INT32).writeInt(i32);
            return BinaryWire.this;
        }

        @Override
        public WireOut uint32checked(long u32) {
            writeCode(UINT32).writeUnsignedInt(u32);
            return BinaryWire.this;
        }

        @Override
        public WireOut float32(float f) {
            writeCode(FLOAT32).writeFloat(f);
            return BinaryWire.this;
        }

        @Override
        public WireOut float64(double d) {
            writeCode(FLOAT64).writeDouble(d);
            return BinaryWire.this;
        }

        @Override
        public WireOut int64(long i64) {
            return fixedInt64(i64);
        }

        private WireOut fixedInt64(long i64) {
            writeCode(INT64).writeLong(i64);
            return BinaryWire.this;
        }

        @Override
        public WireOut time(LocalTime localTime) {
            writeCode(TIME).writeUTFΔ(localTime.toString());
            return BinaryWire.this;
        }

        @Override
        public WireOut zonedDateTime(ZonedDateTime zonedDateTime) {
            writeCode(ZONED_DATE_TIME).writeUTFΔ(zonedDateTime.toString());
            return BinaryWire.this;
        }

        @Override
        public WireOut date(LocalDate localDate) {
            writeCode(DATE).writeUTFΔ(localDate.toString());
            return BinaryWire.this;
        }

        @Override
        public WireOut uuid(UUID uuid) {
            writeCode(UUID).writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits());
            return BinaryWire.this;
        }

        @Override
        public WireOut int64(LongValue longValue) {
            int fromEndOfCacheLine = (int) ((-bytes.position()) & 63);
            if (fromEndOfCacheLine < 9)
                addPadding(fromEndOfCacheLine - 1);
            fixedInt64(longValue.getValue());
            return BinaryWire.this;
        }

        @Override
        public WireOut int32(IntValue value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WireOut sequence(Runnable writer) {
            throw new UnsupportedOperationException();
        }


        public ValueOut writeLength(long length) {
            if (length < 0) {
                throw new IllegalArgumentException("Invalid length " + length);
            } else if (length < 1 << 8) {
                writeCode(BYTES_LENGTH8);
                bytes.writeUnsignedByte((int) length);
            } else if (length < 1 << 16) {
                writeCode(BYTES_LENGTH16);
                bytes.writeUnsignedShort((int) length);
            } else {
                writeCode(BYTES_LENGTH32);
                bytes.writeUnsignedInt(length);
            }
            return this;
        }
    }

    class BinaryValueOut extends FixedBinaryValueOut {
        void writeInt(long l) {
            if (l < Integer.MIN_VALUE) {
                if (l == (float) l)
                    super.float32(l);
                else
                    super.int64(l);
            } else if (l < Short.MIN_VALUE) {
                super.int32((int) l);
            } else if (l < Byte.MIN_VALUE) {
                super.int16((short) l);
            } else if (l < 0) {
                super.int8((byte) l);
            } else if (l < 128) {
                bytes.writeUnsignedByte((int) l);
            } else if (l < 1 << 8) {
                super.uint8checked((short) l);
            } else if (l < 1 << 16) {
                super.uint16checked((int) l);
            } else if (l < 1L << 32) {
                super.uint32checked(l);
            } else {
                if (l == (float) l)
                    super.float32(l);
                else
                    super.int64(l);
            }
        }

        void writeFloat(double d) {
            if (d < 1L << 32 && d >= -1L << 31) {
                long l = (long) d;
                if (l == d) {
                    writeInt(l);
                    return;
                }
            }
            float f = (float) d;
            if (f == d)
                super.float32(f);
            else
                super.float64(d);
        }

        @Override
        public WireOut int8(byte i8) {
            writeInt(i8);
            return BinaryWire.this;
        }

        @Override
        public WireOut uint8checked(int u8) {
            writeInt(u8);
            return BinaryWire.this;
        }

        @Override
        public WireOut int16(short i16) {
            writeInt(i16);
            return BinaryWire.this;
        }

        @Override
        public WireOut uint16checked(int u16) {
            writeInt(u16);
            return BinaryWire.this;
        }

        @Override
        public WireOut int32(int i32) {
            writeInt(i32);
            return BinaryWire.this;
        }

        @Override
        public WireOut uint32checked(long u32) {
            writeInt(u32);
            return BinaryWire.this;
        }

        @Override
        public WireOut float32(float f) {
            writeFloat(f);
            return BinaryWire.this;
        }

        @Override
        public WireOut float64(double d) {
            writeFloat(d);
            return BinaryWire.this;
        }

        @Override
        public WireOut int64(long i64) {
            writeInt(i64);
            return BinaryWire.this;
        }
    }

    class BinaryValueIn implements ValueIn {
        @Override
        public boolean hasNext() {
            return false;
        }

        public WireIn bytes(Bytes toBytes) {
            long length = readLength();
            int code = readCode();
            if (code != U8_ARRAY)
                cantRead(code);
            bytes.withLength(length - 1, toBytes::write);
            return wireIn();
        }

        public WireIn bytes(Consumer<byte[]> bytesConsumer) {
            long length = readLength();
            int code = readCode();
            if (code != U8_ARRAY)
                cantRead(code);
            byte[] byteArray = new byte[Maths.toInt32(length - 1)];
            bytes.read(byteArray);
            bytesConsumer.accept(byteArray);
            return wireIn();
        }

        @Override
        public byte[] bytes() {
            throw new UnsupportedOperationException("todo");
        }

        @Override
        public WireIn type(StringBuilder s) {
            int code = readCode();
            if (code == TYPE) {
                bytes.readUTFΔ(s);
            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Override
        public WireIn text(StringBuilder s) {
            int code = readCode();
            StringBuilder text = readText(code, s);
            if (text == null)
                cantRead(code);
            return BinaryWire.this;
        }

        @Override
        public WireIn bool(BooleanConsumer flag) {
            consumeSpecial();
            int code = readCode();
            switch (code) {
                case NULL:
                    // todo take the default.
                    flag.accept(null);
                    break;
                case FALSE:
                    flag.accept(false);
                    break;
                case TRUE:
                    flag.accept(true);
                    break;
                default:
                    return cantRead(code);
            }
            return BinaryWire.this;
        }

        private WireIn cantRead(int code) {
            throw new UnsupportedOperationException(stringForCode(code));
        }

        @Override
        public WireIn int8(ByteConsumer i) {
            consumeSpecial();
            i.accept((byte) readInt(bytes.readUnsignedByte()));
            return BinaryWire.this;
        }

        @Override
        public WireIn wireIn() {
            return BinaryWire.this;
        }

        @Override
        public WireIn uint8(ShortConsumer i) {
            consumeSpecial();
            i.accept((short) readInt(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn int16(ShortConsumer i) {
            i.accept((short) readInt(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn uint16(IntConsumer i) {
            consumeSpecial();
            i.accept((int) readInt(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn uint32(LongConsumer i) {
            consumeSpecial();
            i.accept(readInt(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn int32(IntConsumer i) {
            consumeSpecial();
            i.accept((int) readInt(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn float32(FloatConsumer v) {
            consumeSpecial();
            v.accept((float) readFloat(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn float64(DoubleConsumer v) {
            v.accept(readFloat(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn int64(LongConsumer i) {
            i.accept(readInt(readCode()));
            return BinaryWire.this;
        }

        @Override
        public WireIn time(Consumer<LocalTime> localTime) {
            consumeSpecial();
            int code = readCode();
            if (code == TIME) {
                StringBuilder sb = Wires.acquireStringBuilder();
                bytes.readUTFΔ(sb);
                localTime.accept(LocalTime.parse(sb));
            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Override
        public WireIn zonedDateTime(Consumer<ZonedDateTime> zonedDateTime) {
            consumeSpecial();
            int code = readCode();
            if (code == ZONED_DATE_TIME) {
                StringBuilder sb = Wires.acquireStringBuilder();
                bytes.readUTFΔ(sb);
                zonedDateTime.accept(ZonedDateTime.parse(sb));
            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Override
        public WireIn date(Consumer<LocalDate> localDate) {
            consumeSpecial();
            int code = readCode();
            if (code == DATE) {
                StringBuilder sb = Wires.acquireStringBuilder();
                bytes.readUTFΔ(sb);
                localDate.accept(LocalDate.parse(sb));
            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Override
        public WireIn text(Consumer<String> s) {
            consumeSpecial();
            int code = readCode();
            switch (code) {
                case NULL:
                    s.accept(null);
                    break;
                case STRING_ANY:
                    s.accept(bytes.readUTFΔ());
                    break;
                default:
                    if (code >= STRING0 && code <= STRING30) {
                        StringBuilder sb = Wires.acquireStringBuilder();
                        BytesUtil.parseUTF(bytes, sb, code & 0b11111);
                        s.accept(sb.toString());
                    } else {
                        cantRead(code);
                    }
            }
            return BinaryWire.this;
        }

        @Override
        public WireIn expectText(CharSequence s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WireIn uuid(Consumer<UUID> uuid) {
            consumeSpecial();
            int code = readCode();
            if (code == UUID) {
                uuid.accept(new UUID(bytes.readLong(), bytes.readLong()));
            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Override
        public WireIn int64(LongValue value) {
            consumeSpecial();
            int code = readCode();
            if (code != INT64)
                cantRead(code);
            Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytes(bytes, bytes.position(), length);
            bytes.skip(length);
            return BinaryWire.this;
        }


        @Override
        public boolean bool() {
            consumeSpecial();
            int code = readCode();
            if (code != UINT8)
                cantRead(code);

            switch (bytes.readUnsignedByte()) {
                case TRUE:
                    return true;
                case FALSE:
                    return false;
            }
            throw new IllegalStateException();
        }

        @Override
        public byte int8() {
            consumeSpecial();
            int code = readCode();
            if (code != INT8)
                cantRead(code);
            return bytes.readByte();
        }

        @Override
        public short int16() {
            consumeSpecial();
            int code = readCode();
            if (code != INT16)
                cantRead(code);
            return bytes.readShort();
        }

        @Override
        public int int32() {
            consumeSpecial();
            int code = readCode();
            if (code != INT32)
                cantRead(code);
            return bytes.readInt();
        }
        @Override
        public long int64() {
            consumeSpecial();
            int code = readCode();
            if (code != INT64)
                cantRead(code);
            return bytes.readLong();
        }

        @Override
        public double float64() {
            throw new UnsupportedOperationException("todo");
        }

        @Override
        public float float32() {
            throw new UnsupportedOperationException("todo");
        }

        @Override
        public WireIn int32(IntValue value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WireIn sequence(Consumer<ValueIn> reader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WireIn marshallable(Marshallable object) {
            consumeSpecial();
            long length = readLength();
            if (length >= 0) {
                long limit = bytes.readLimit();
                long limit2 = bytes.position() + length;
                bytes.limit(limit2);
                try {
                    object.readMarshallable(BinaryWire.this);
                } finally {
                    bytes.limit(limit);
                    bytes.position(limit2);
                }
            } else {
                object.readMarshallable(BinaryWire.this);
            }
            return BinaryWire.this;
        }

        @Override
        public long readLength() {
            int code = peekCode();
            // TODO handle non length types as well.
            switch (code) {
                case BYTES_LENGTH8:
                    bytes.skip(1);
                    return bytes.readUnsignedByte();
                case BYTES_LENGTH16:
                    bytes.skip(1);
                    return bytes.readUnsignedShort();
                case BYTES_LENGTH32:
                    bytes.skip(1);
                    return bytes.readUnsignedInt();
                default:
                    return ANY_CODE_MATCH;
            }
        }
    }
}
