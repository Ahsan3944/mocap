package net.mt1006.mocap.network;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;

public class NetworkUtils
{
	private static final int MAX_STRING_BYTES = 1 << 20;
	private static final int MAX_BYTE_ARRAY_BYTES = 20 * (1 << 20);

	public static void writeString(FriendlyByteBuf buf, String str)
	{
		byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_STRING_BYTES) { throw new IllegalArgumentException("String exceeds Mocap packet limit"); }
		buf.writeInt(bytes.length);
		buf.writeBytes(bytes);
	}

	public static String readString(FriendlyByteBuf buf)
	{
		int length = buf.readInt();
		if (length < 0 || length > MAX_STRING_BYTES || length > buf.readableBytes())
		{ throw new IllegalArgumentException("Invalid Mocap packet string length: " + length); }
		byte[] byteArray = new byte[length];
		buf.readBytes(byteArray);
		return new String(byteArray, StandardCharsets.UTF_8);
	}

	public static void writeByteArray(FriendlyByteBuf buf, byte[] array)
	{
		if (array.length > MAX_BYTE_ARRAY_BYTES) { throw new IllegalArgumentException("Byte array exceeds Mocap packet limit"); }
		buf.writeInt(array.length);
		buf.writeBytes(array);
	}

	public static byte[] readByteArray(FriendlyByteBuf buf)
	{
		int length = buf.readInt();
		if (length < 0 || length > MAX_BYTE_ARRAY_BYTES || length > buf.readableBytes())
		{ throw new IllegalArgumentException("Invalid Mocap packet byte-array length: " + length); }
		byte[] byteArray = new byte[length];
		buf.readBytes(byteArray);
		return byteArray;
	}
}
