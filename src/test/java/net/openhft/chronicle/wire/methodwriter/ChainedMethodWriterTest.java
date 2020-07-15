package net.openhft.chronicle.wire.methodwriter;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.core.Mocker;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.Wire;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChainedMethodWriterTest {
    @Test
    public void toPublisher() {
        Wire wire = new TextWire(Bytes.allocateElasticOnHeap());
        ToPublisher toPublisher = wire.methodWriter(ToPublisher.class);
        toPublisher.to(1234).say("hello");
        assertEquals("to: 1234\n" +
                        "say: Hello",
                wire.bytes().toString());
        StringBuilder sb = new StringBuilder();
        MethodReader reader = wire.methodReader(Mocker.intercepting(ToPublisher.class, "", sb::append));
        assertTrue(reader.readOne());
        assertFalse(reader.readOne());
        assertEquals("to: 1234\n" +
                "say: Hello", sb.toString());
    }

    interface To<T> {
        T to(long dest);
    }

    interface Publisher {
        void say(String text);
    }

    interface ToPublisher extends To<Publisher> {
        @Override
        Publisher to(long dest);
    }
}
