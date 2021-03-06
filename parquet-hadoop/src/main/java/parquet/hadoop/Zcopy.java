/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.hadoop;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.ReadOption;
import org.apache.hadoop.io.ByteBufferPool;
import org.apache.hadoop.io.ElasticByteBufferPool;
import parquet.format.FileMetaData;
import parquet.org.apache.thrift.ShortStack;
import parquet.org.apache.thrift.TBase;
import parquet.org.apache.thrift.TException;
import parquet.org.apache.thrift.protocol.*;
import parquet.org.apache.thrift.transport.TTransport;
import parquet.org.apache.thrift.transport.TTransportException;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.EnumSet;

public class Zcopy {
  private static final ByteBufferPool bufferPool = new ElasticByteBufferPool();
  private static final EnumSet<ReadOption> ZCOPY_OPTS =
      EnumSet.of(ReadOption.SKIP_CHECKSUMS);
  private static final int MAX_SIZE = 1 << 20;

  public static int getInt(FSDataInputStream f) throws IOException {
    ByteBuffer int32Buf = getBuf(f, 4).order(ByteOrder.LITTLE_ENDIAN);
    if (int32Buf.remaining() == 4) {
      final int res = int32Buf.getInt();
      f.releaseBuffer(int32Buf);
      return res;
    }
    ByteBuffer tmpBuf = int32Buf;
    int32Buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    int32Buf.put(tmpBuf);
    f.releaseBuffer(tmpBuf);
    while (int32Buf.hasRemaining()) {
      tmpBuf = getBuf(f, int32Buf.remaining());
      int32Buf.put(tmpBuf);
      f.releaseBuffer(tmpBuf);
    }
    return int32Buf.getInt();
  }

  public static ByteBuffer getBuf(FSDataInputStream f, int maxSize)
      throws IOException {
    final ByteBuffer res = f.read(bufferPool, maxSize, ZCOPY_OPTS);
    if (res == null) {
      throw new EOFException("Null ByteBuffer returned");
    }
    return res;
  }

  public static void bbCopy(ByteBuffer dst, ByteBuffer src) {
    final int n = Math.min(dst.remaining(), src.remaining());
    for (int i = 0; i < n; i++) {
      dst.put(src.get());
    }
  }

  // borrowing logic from parquet.format.Util
  //
  public static FileMetaData readFileMetaData(FSDataInputStream f)
      throws IOException {
    return read(f, new FileMetaData());
  }

  private static <T extends TBase<?,?>> T read(FSDataInputStream f, T tbase)
      throws IOException {
    try {
      tbase.read(new TCompactProtocol(new FSDISTransport(f)));
      return tbase;
    } catch (TException e) {
      throw new IOException("can not read " + tbase.getClass() + ": "
          + e.getMessage(), e);
    }
  }

  private static final class FSDISTransport extends TTransport {
    private final FSDataInputStream fsdis;
    // ByteBuffer-based API
    private ByteBuffer tbuf;
    private ByteBuffer slice;

    private FSDISTransport(FSDataInputStream f) {
      super();
      fsdis = f;
    }

    @Override
    public boolean isOpen() {
      return true; // TODO
    }

    @Override
    public boolean peek() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void open() throws TTransportException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int read(byte[] bytes, int i, int i2) throws TTransportException {
      throw new UnsupportedOperationException("ByteBuffer API to be used");
    }

    @Override
    public int readAll(byte[] buf, int off, int len) throws TTransportException {
      throw new UnsupportedOperationException("ByteBuffer API to be used");
    }

    @Override
    public void write(byte[] buf) throws TTransportException {
      throw new UnsupportedOperationException("Read-Only implementation");
    }

    @Override
    public void write(byte[] bytes, int i, int i2) throws TTransportException {
      throw new UnsupportedOperationException("Read-Only implementation");
    }

    @Override
    public void flush() throws TTransportException {
      throw new UnsupportedOperationException("Read-Only implementation");
    }

    @Override
    public byte[] getBuffer() {
      throw new UnsupportedOperationException("ByteBuffer API to be used");
    }

    @Override
    public int getBufferPosition() {
      throw new UnsupportedOperationException("ByteBuffer API to be used");
    }

    @Override
    public int getBytesRemainingInBuffer() {
      throw new UnsupportedOperationException("ByteBuffer API to be used");
    }

    @Override
    public void consumeBuffer(int len) {
      throw new UnsupportedOperationException("ByteBuffer API to be used");
    }

    public byte readByte() throws TTransportException {
      try {
        for (;;) {
          if (tbuf == null) {
            tbuf = getBuf(fsdis, MAX_SIZE);
          }
          if (tbuf.hasRemaining()) {
            return tbuf.get();
          } else {
            release(tbuf);
          }
        }
      } catch (IOException ioe) {
        throw new TTransportException("Hadoop FS", ioe);
      } finally {
        release(tbuf);
      }
    }

    public ByteBuffer readFully(int size) throws TTransportException {
      try {
        ByteBuffer newBuf = null; // crossing boundaries
        for (;;) {
          if (tbuf == null) {
            tbuf = getBuf(fsdis, MAX_SIZE);
          }
          if (newBuf == null) {
            // serve slice from I/O buffer?
            if (tbuf.remaining() >= size) {
              final int lim = tbuf.limit();
              tbuf.limit(tbuf.position() + size);
              slice = tbuf.slice();
              tbuf.position(tbuf.limit());
              tbuf.limit(lim);
              return slice;
            } else {
              newBuf = bufferPool.getBuffer(false, size);
              newBuf.limit(size).position(0);
            }
          }
          // no zero copy
          bbCopy(newBuf, tbuf);
          release(tbuf);
          if (!newBuf.hasRemaining()) {
            newBuf.flip();
            if (newBuf.remaining() != size) {
              throw new TTransportException("boom");
            }
            return newBuf;
          }
        }
      } catch (IOException ioe) {
        throw new TTransportException("Hadoop FS", ioe);
      }
    }

    public void release(ByteBuffer b) {
      if (b == null) {
        return;
      } else if (b == slice) {
        slice = null;
      } else if (b == tbuf) {
        if (!tbuf.hasRemaining()) {
          fsdis.releaseBuffer(tbuf);
          tbuf = null;
        }
      } else {
        bufferPool.putBuffer(b);
      }
    }
  }

  // TODO hack because Thrift declares TCompactProtocol final, but we need to
  // avoid byte arrays
  //
  private static final class TCompactProtocol extends TProtocol {

    private final FSDISTransport fsdisT;
    private final CharsetDecoder utf8dec;

    private final static TStruct ANONYMOUS_STRUCT = new TStruct("");
    private final static TField TSTOP = new TField("", TType.STOP, (short)0);

    private final static byte[] ttypeToCompactType = new byte[16];

    static {
      ttypeToCompactType[TType.STOP] = TType.STOP;
      ttypeToCompactType[TType.BOOL] = Types.BOOLEAN_TRUE;
      ttypeToCompactType[TType.BYTE] = Types.BYTE;
      ttypeToCompactType[TType.I16] = Types.I16;
      ttypeToCompactType[TType.I32] = Types.I32;
      ttypeToCompactType[TType.I64] = Types.I64;
      ttypeToCompactType[TType.DOUBLE] = Types.DOUBLE;
      ttypeToCompactType[TType.STRING] = Types.BINARY;
      ttypeToCompactType[TType.LIST] = Types.LIST;
      ttypeToCompactType[TType.SET] = Types.SET;
      ttypeToCompactType[TType.MAP] = Types.MAP;
      ttypeToCompactType[TType.STRUCT] = Types.STRUCT;
    }

    private static final byte PROTOCOL_ID = (byte)0x82;
    private static final byte VERSION = 1;
    private static final byte VERSION_MASK = 0x1f; // 0001 1111
    private static final byte TYPE_MASK = (byte)0xE0; // 1110 0000
    private static final int  TYPE_SHIFT_AMOUNT = 5;

    /**
     * All of the on-wire type codes.
     */
    private class Types {
      public static final byte BOOLEAN_TRUE   = 0x01;
      public static final byte BOOLEAN_FALSE  = 0x02;
      public static final byte BYTE           = 0x03;
      public static final byte I16            = 0x04;
      public static final byte I32            = 0x05;
      public static final byte I64            = 0x06;
      public static final byte DOUBLE         = 0x07;
      public static final byte BINARY         = 0x08;
      public static final byte LIST           = 0x09;
      public static final byte SET            = 0x0A;
      public static final byte MAP            = 0x0B;
      public static final byte STRUCT         = 0x0C;
    }

    /**
     * Used to keep track of the last field for the current and previous structs,
     * so we can do the delta stuff.
     */
    private ShortStack lastField_ = new ShortStack(15);

    private short lastFieldId_ = 0;

    /**
     * If we encounter a boolean field begin, save the TField here so it can
     * have the value incorporated.
     */
    private TField booleanField_ = null;

    /**
     * If we read a field header, and it's a boolean field, save the boolean
     * value here so that readBool can use it.
     */
    private Boolean boolValue_ = null;

    /**
     * Create a TCompactProtocol.
     *
     * @param transport the TTransport object to read from or write to.
     */
    public TCompactProtocol(TTransport transport) {
      super(transport);
      fsdisT = (FSDISTransport)transport;
      utf8dec = Charset.forName("UTF-8").newDecoder();
    }

    @Override
    public void reset() {
      lastField_.clear();
      lastFieldId_ = 0;
    }

    //
    // Public Writing methods.
    //

    /**
     * Write a message header to the wire. Compact Protocol messages contain the
     * protocol version so we can migrate forwards in the future if need be.
     */
    public void writeMessageBegin(TMessage message) throws TException {
      writeByteDirect(PROTOCOL_ID);
      writeByteDirect((VERSION & VERSION_MASK) | ((message.type << TYPE_SHIFT_AMOUNT) & TYPE_MASK));
      writeVarint32(message.seqid);
      writeString(message.name);
    }

    /**
     * Write a struct begin. This doesn't actually put anything on the wire. We
     * use it as an opportunity to put special placeholder markers on the field
     * stack so we can get the field id deltas correct.
     */
    public void writeStructBegin(TStruct struct) throws TException {
      lastField_.push(lastFieldId_);
      lastFieldId_ = 0;
    }

    /**
     * Write a struct end. This doesn't actually put anything on the wire. We use
     * this as an opportunity to pop the last field from the current struct off
     * of the field stack.
     */
    public void writeStructEnd() throws TException {
      lastFieldId_ = lastField_.pop();
    }

    /**
     * Write a field header containing the field id and field type. If the
     * difference between the current field id and the last one is small (< 15),
     * then the field id will be encoded in the 4 MSB as a delta. Otherwise, the
     * field id will follow the type header as a zigzag varint.
     */
    public void writeFieldBegin(TField field) throws TException {
      if (field.type == TType.BOOL) {
        // we want to possibly include the value, so we'll wait.
        booleanField_ = field;
      } else {
        writeFieldBeginInternal(field, (byte)-1);
      }
    }

    /**
     * The workhorse of writeFieldBegin. It has the option of doing a
     * 'type override' of the type header. This is used specifically in the
     * boolean field case.
     */
    private void writeFieldBeginInternal(TField field, byte typeOverride) throws TException {
      // short lastField = lastField_.pop();

      // if there's a type override, use that.
      byte typeToWrite = typeOverride == -1 ? getCompactType(field.type) : typeOverride;

      // check if we can use delta encoding for the field id
      if (field.id > lastFieldId_ && field.id - lastFieldId_ <= 15) {
        // write them together
        writeByteDirect((field.id - lastFieldId_) << 4 | typeToWrite);
      } else {
        // write them separate
        writeByteDirect(typeToWrite);
        writeI16(field.id);
      }

      lastFieldId_ = field.id;
      // lastField_.push(field.id);
    }

    /**
     * Write the STOP symbol so we know there are no more fields in this struct.
     */
    public void writeFieldStop() throws TException {
      writeByteDirect(TType.STOP);
    }

    /**
     * Write a map header. If the map is empty, omit the key and value type
     * headers, as we don't need any additional information to skip it.
     */
    public void writeMapBegin(TMap map) throws TException {
      if (map.size == 0) {
        writeByteDirect(0);
      } else {
        writeVarint32(map.size);
        writeByteDirect(getCompactType(map.keyType) << 4 | getCompactType(map.valueType));
      }
    }

    /**
     * Write a list header.
     */
    public void writeListBegin(TList list) throws TException {
      writeCollectionBegin(list.elemType, list.size);
    }

    /**
     * Write a set header.
     */
    public void writeSetBegin(TSet set) throws TException {
      writeCollectionBegin(set.elemType, set.size);
    }

    /**
     * Write a boolean value. Potentially, this could be a boolean field, in
     * which case the field header info isn't written yet. If so, decide what the
     * right type header is for the value and then write the field header.
     * Otherwise, write a single byte.
     */
    public void writeBool(boolean b) throws TException {
      if (booleanField_ != null) {
        // we haven't written the field header yet
        writeFieldBeginInternal(booleanField_, b ? Types.BOOLEAN_TRUE : Types.BOOLEAN_FALSE);
        booleanField_ = null;
      } else {
        // we're not part of a field, so just write the value.
        writeByteDirect(b ? Types.BOOLEAN_TRUE : Types.BOOLEAN_FALSE);
      }
    }

    /**
     * Write a byte. Nothing to see here!
     */
    public void writeByte(byte b) throws TException {
      writeByteDirect(b);
    }

    /**
     * Write an I16 as a zigzag varint.
     */
    public void writeI16(short i16) throws TException {
      writeVarint32(intToZigZag(i16));
    }

    /**
     * Write an i32 as a zigzag varint.
     */
    public void writeI32(int i32) throws TException {
      writeVarint32(intToZigZag(i32));
    }

    /**
     * Write an i64 as a zigzag varint.
     */
    public void writeI64(long i64) throws TException {
      writeVarint64(longToZigzag(i64));
    }

    /**
     * Write a double to the wire as 8 bytes.
     */
    public void writeDouble(double dub) throws TException {
      byte[] data = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};
      fixedLongToBytes(Double.doubleToLongBits(dub), data, 0);
      trans_.write(data);
    }

    /**
     * Write a string to the wire with a varint size preceding.
     */
    public void writeString(String str) throws TException {
      try {
        byte[] bytes = str.getBytes("UTF-8");
        writeBinary(bytes, 0, bytes.length);
      } catch (UnsupportedEncodingException e) {
        throw new TException("UTF-8 not supported!");
      }
    }

    /**
     * Write a byte array, using a varint for the size.
     */
    public void writeBinary(ByteBuffer bin) throws TException {
      int length = bin.limit() - bin.position();
      writeBinary(bin.array(), bin.position() + bin.arrayOffset(), length);
    }

    private void writeBinary(byte[] buf, int offset, int length) throws TException {
      writeVarint32(length);
      trans_.write(buf, offset, length);
    }

    //
    // These methods are called by structs, but don't actually have any wire
    // output or purpose.
    //

    public void writeMessageEnd() throws TException {}
    public void writeMapEnd() throws TException {}
    public void writeListEnd() throws TException {}
    public void writeSetEnd() throws TException {}
    public void writeFieldEnd() throws TException {}

    //
    // Internal writing methods
    //

    /**
     * Abstract method for writing the start of lists and sets. List and sets on
     * the wire differ only by the type indicator.
     */
    protected void writeCollectionBegin(byte elemType, int size) throws TException {
      if (size <= 14) {
        writeByteDirect(size << 4 | getCompactType(elemType));
      } else {
        writeByteDirect(0xf0 | getCompactType(elemType));
        writeVarint32(size);
      }
    }

    /**
     * Write an i32 as a varint. Results in 1-5 bytes on the wire.
     * TODO: make a permanent buffer like writeVarint64?
     */
    byte[] i32buf = new byte[5];
    private void writeVarint32(int n) throws TException {
      int idx = 0;
      while (true) {
        if ((n & ~0x7F) == 0) {
          i32buf[idx++] = (byte)n;
          // writeByteDirect((byte)n);
          break;
          // return;
        } else {
          i32buf[idx++] = (byte)((n & 0x7F) | 0x80);
          // writeByteDirect((byte)((n & 0x7F) | 0x80));
          n >>>= 7;
        }
      }
      trans_.write(i32buf, 0, idx);
    }

    /**
     * Write an i64 as a varint. Results in 1-10 bytes on the wire.
     */
    byte[] varint64out = new byte[10];
    private void writeVarint64(long n) throws TException {
      int idx = 0;
      while (true) {
        if ((n & ~0x7FL) == 0) {
          varint64out[idx++] = (byte)n;
          break;
        } else {
          varint64out[idx++] = ((byte)((n & 0x7F) | 0x80));
          n >>>= 7;
        }
      }
      trans_.write(varint64out, 0, idx);
    }

    /**
     * Convert l into a zigzag long. This allows negative numbers to be
     * represented compactly as a varint.
     */
    private long longToZigzag(long l) {
      return (l << 1) ^ (l >> 63);
    }

    /**
     * Convert n into a zigzag int. This allows negative numbers to be
     * represented compactly as a varint.
     */
    private int intToZigZag(int n) {
      return (n << 1) ^ (n >> 31);
    }

    /**
     * Convert a long into little-endian bytes in buf starting at off and going
     * until off+7.
     */
    private void fixedLongToBytes(long n, byte[] buf, int off) {
      buf[off+0] = (byte)( n        & 0xff);
      buf[off+1] = (byte)((n >> 8 ) & 0xff);
      buf[off+2] = (byte)((n >> 16) & 0xff);
      buf[off+3] = (byte)((n >> 24) & 0xff);
      buf[off+4] = (byte)((n >> 32) & 0xff);
      buf[off+5] = (byte)((n >> 40) & 0xff);
      buf[off+6] = (byte)((n >> 48) & 0xff);
      buf[off+7] = (byte)((n >> 56) & 0xff);
    }

    /**
     * Writes a byte without any possibility of all that field header nonsense.
     * Used internally by other writing methods that know they need to write a byte.
     */
    private byte[] byteDirectBuffer = new byte[1];
    private void writeByteDirect(byte b) throws TException {
      byteDirectBuffer[0] = b;
      trans_.write(byteDirectBuffer);
    }

    /**
     * Writes a byte without any possibility of all that field header nonsense.
     */
    private void writeByteDirect(int n) throws TException {
      writeByteDirect((byte)n);
    }


    //
    // Reading methods.
    //

    /**
     * Read a message header.
     */
    public TMessage readMessageBegin() throws TException {
      byte protocolId = readByte();
      if (protocolId != PROTOCOL_ID) {
        throw new TProtocolException("Expected protocol id " + Integer.toHexString(PROTOCOL_ID) + " but got " + Integer.toHexString(protocolId));
      }
      byte versionAndType = readByte();
      byte version = (byte)(versionAndType & VERSION_MASK);
      if (version != VERSION) {
        throw new TProtocolException("Expected version " + VERSION + " but got " + version);
      }
      byte type = (byte)((versionAndType >> TYPE_SHIFT_AMOUNT) & 0x03);
      int seqid = readVarint32();
      String messageName = readString();
      return new TMessage(messageName, type, seqid);
    }

    /**
     * Read a struct begin. There's nothing on the wire for this, but it is our
     * opportunity to push a new struct begin marker onto the field stack.
     */
    public TStruct readStructBegin() throws TException {
      lastField_.push(lastFieldId_);
      lastFieldId_ = 0;
      return ANONYMOUS_STRUCT;
    }

    /**
     * Doesn't actually consume any wire data, just removes the last field for
     * this struct from the field stack.
     */
    public void readStructEnd() throws TException {
      // consume the last field we read off the wire.
      lastFieldId_ = lastField_.pop();
    }

    /**
     * Read a field header off the wire.
     */
    public TField readFieldBegin() throws TException {
      byte type = readByte();

      // if it's a stop, then we can return immediately, as the struct is over.
      if (type == TType.STOP) {
        return TSTOP;
      }

      short fieldId;

      // mask off the 4 MSB of the type header. it could contain a field id delta.
      short modifier = (short)((type & 0xf0) >> 4);
      if (modifier == 0) {
        // not a delta. look ahead for the zigzag varint field id.
        fieldId = readI16();
      } else {
        // has a delta. add the delta to the last read field id.
        fieldId = (short)(lastFieldId_ + modifier);
      }

      TField field = new TField("", getTType((byte)(type & 0x0f)), fieldId);

      // if this happens to be a boolean field, the value is encoded in the type
      if (isBoolType(type)) {
        // save the boolean value in a special instance variable.
        boolValue_ = (byte)(type & 0x0f) == Types.BOOLEAN_TRUE ? Boolean.TRUE : Boolean.FALSE;
      }

      // push the new field onto the field stack so we can keep the deltas going.
      lastFieldId_ = field.id;
      return field;
    }

    /**
     * Read a map header off the wire. If the size is zero, skip reading the key
     * and value type. This means that 0-length maps will yield TMaps without the
     * "correct" types.
     */
    public TMap readMapBegin() throws TException {
      int size = readVarint32();
      byte keyAndValueType = size == 0 ? 0 : readByte();
      return new TMap(getTType((byte)(keyAndValueType >> 4)), getTType((byte)(keyAndValueType & 0xf)), size);
    }

    /**
     * Read a list header off the wire. If the list size is 0-14, the size will
     * be packed into the element type header. If it's a longer list, the 4 MSB
     * of the element type header will be 0xF, and a varint will follow with the
     * true size.
     */
    public TList readListBegin() throws TException {
      byte size_and_type = readByte();
      int size = (size_and_type >> 4) & 0x0f;
      if (size == 15) {
        size = readVarint32();
      }
      byte type = getTType(size_and_type);
      return new TList(type, size);
    }

    /**
     * Read a set header off the wire. If the set size is 0-14, the size will
     * be packed into the element type header. If it's a longer set, the 4 MSB
     * of the element type header will be 0xF, and a varint will follow with the
     * true size.
     */
    public TSet readSetBegin() throws TException {
      return new TSet(readListBegin());
    }

    /**
     * Read a boolean off the wire. If this is a boolean field, the value should
     * already have been read during readFieldBegin, so we'll just consume the
     * pre-stored value. Otherwise, read a byte.
     */
    public boolean readBool() throws TException {
      if (boolValue_ != null) {
        boolean result = boolValue_.booleanValue();
        boolValue_ = null;
        return result;
      }
      return readByte() == Types.BOOLEAN_TRUE;
    }

    byte[] byteRawBuf = new byte[1];
    /**
     * Read a single byte off the wire. Nothing interesting here.
     */
    public byte readByte() throws TException {
      return fsdisT.readByte();
    }

    /**
     * Read an i16 from the wire as a zigzag varint.
     */
    public short readI16() throws TException {
      return (short)zigzagToInt(readVarint32());
    }

    /**
     * Read an i32 from the wire as a zigzag varint.
     */
    public int readI32() throws TException {
      return zigzagToInt(readVarint32());
    }

    /**
     * Read an i64 from the wire as a zigzag varint.
     */
    public long readI64() throws TException {
      return zigzagToLong(readVarint64());
    }

    /**
     * No magic here - just read a double off the wire.
     */
    public double readDouble() throws TException {
      byte[] longBits = new byte[8];
      trans_.readAll(longBits, 0, 8);
      return Double.longBitsToDouble(bytesToLong(longBits));
    }

    /**
     * Reads a byte[] (via readBinary), and then UTF-8 decodes it.
     */
    public String readString() throws TException {
      final int length = readVarint32();

      if (length == 0) {
        return "";
      }

      final ByteBuffer buf = fsdisT.readFully(length);
      try {
        return utf8dec.decode(buf).toString();
      } catch (CharacterCodingException e) {
        throw new TException("UTF-8 decoder", e);
      } finally {
        fsdisT.release(buf);
      }
    }

    public ByteBuffer readBinary() throws TException {
      int length = readVarint32();
      if (length == 0) return ByteBuffer.wrap(new byte[0]);
      return fsdisT.readFully(length);
    }

    //
    // These methods are here for the struct to call, but don't have any wire
    // encoding.
    //
    public void readMessageEnd() throws TException {}
    public void readFieldEnd() throws TException {}
    public void readMapEnd() throws TException {}
    public void readListEnd() throws TException {}
    public void readSetEnd() throws TException {}

    //
    // Internal reading methods
    //

    /**
     * Read an i32 from the wire as a varint. The MSB of each byte is set
     * if there is another byte to follow. This can read up to 5 bytes.
     */
    private int readVarint32() throws TException {
      int result = 0;
      int shift = 0;
      while (true) {
        byte b = readByte();
        result |= (int) (b & 0x7f) << shift;
        if ((b & 0x80) != 0x80) break;
        shift += 7;
      }
      return result;
    }

    /**
     * Read an i64 from the wire as a proper varint. The MSB of each byte is set
     * if there is another byte to follow. This can read up to 10 bytes.
     */
    private long readVarint64() throws TException {
      int shift = 0;
      long result = 0;
      while (true) {
        byte b = readByte();
        result |= (long) (b & 0x7f) << shift;
        if ((b & 0x80) != 0x80) break;
        shift +=7;
      }
      return result;
    }

    //
    // encoding helpers
    //

    /**
     * Convert from zigzag int to int.
     */
    private int zigzagToInt(int n) {
      return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Convert from zigzag long to long.
     */
    private long zigzagToLong(long n) {
      return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Note that it's important that the mask bytes are long literals,
     * otherwise they'll default to ints, and when you shift an int left 56 bits,
     * you just get a messed up int.
     */
    private long bytesToLong(byte[] bytes) {
      return
        ((bytes[7] & 0xffL) << 56) |
        ((bytes[6] & 0xffL) << 48) |
        ((bytes[5] & 0xffL) << 40) |
        ((bytes[4] & 0xffL) << 32) |
        ((bytes[3] & 0xffL) << 24) |
        ((bytes[2] & 0xffL) << 16) |
        ((bytes[1] & 0xffL) <<  8) |
        ((bytes[0] & 0xffL));
    }

    //
    // type testing and converting
    //

    private boolean isBoolType(byte b) {
      int lowerNibble = b & 0x0f;
      return lowerNibble == Types.BOOLEAN_TRUE || lowerNibble == Types.BOOLEAN_FALSE;
    }

    /**
     * Given a TCompactProtocol.Types constant, convert it to its corresponding
     * TType value.
     */
    private byte getTType(byte type) throws TProtocolException {
      switch ((byte)(type & 0x0f)) {
        case TType.STOP:
          return TType.STOP;
        case Types.BOOLEAN_FALSE:
        case Types.BOOLEAN_TRUE:
          return TType.BOOL;
        case Types.BYTE:
          return TType.BYTE;
        case Types.I16:
          return TType.I16;
        case Types.I32:
          return TType.I32;
        case Types.I64:
          return TType.I64;
        case Types.DOUBLE:
          return TType.DOUBLE;
        case Types.BINARY:
          return TType.STRING;
        case Types.LIST:
          return TType.LIST;
        case Types.SET:
          return TType.SET;
        case Types.MAP:
          return TType.MAP;
        case Types.STRUCT:
          return TType.STRUCT;
        default:
          throw new TProtocolException("don't know what type: " + (byte)(type & 0x0f));
      }
    }

    /**
     * Given a TType value, find the appropriate TCompactProtocol.Types constant.
     */
    private byte getCompactType(byte ttype) {
      return ttypeToCompactType[ttype];
    }
  }
}
