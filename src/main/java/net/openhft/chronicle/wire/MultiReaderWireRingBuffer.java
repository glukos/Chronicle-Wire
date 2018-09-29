package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.MultiReaderBytesRingBuffer;
import net.openhft.chronicle.bytes.RingBufferReader;
import org.jetbrains.annotations.NotNull;

import java.nio.BufferOverflowException;

/**
 * Created by Rob Austin
 */
public interface MultiReaderWireRingBuffer extends MultiReaderBytesRingBuffer {

    @NotNull
    default DocumentContext writingDocument() throws UnrecoverableTimeoutException {
        return writingDocument(false);
    }

    @NotNull
    DocumentContext writingDocument(boolean metaData) throws UnrecoverableTimeoutException;

    void endRead(long next) throws BufferOverflowException;

    @NotNull
    RingBufferReader createReader();
}