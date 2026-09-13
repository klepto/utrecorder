package dev.klepto.utrecorder.ut99

import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes

/*
 * Shared between UtPackageTest and UtInstallationTest, which both list the packages of a directory holding package
 * files of a known GUID.
 */

/**
 * The GUID the header [writePackage] writes holds.
 */
const val GUID = "123456789ABCDEF0876543210BADF00D"

/**
 * A GUID another version of the same package holds.
 */
const val OTHER_GUID = "11111111222222223333333344444444"

/**
 * The bytes a package header holds at [GUID_OFFSET] for a package of GUID [GUID].
 */
val GUID_HEADER_BYTES =
    bytesOf(0x78, 0x56, 0x34, 0x12, 0xF0, 0xDE, 0xBC, 0x9A, 0x21, 0x43, 0x65, 0x87, 0x0D, 0xF0, 0xAD, 0x0B)

/**
 * The bytes a package header holds at [GUID_OFFSET] for a package of GUID [OTHER_GUID].
 */
val OTHER_GUID_HEADER_BYTES =
    bytesOf(0x11, 0x11, 0x11, 0x11, 0x22, 0x22, 0x22, 0x22, 0x33, 0x33, 0x33, 0x33, 0x44, 0x44, 0x44, 0x44)

/**
 * The bytes a package header opens with: the package tag, then the file version and the licensee version.
 */
private val TAG_HEADER_BYTES = bytesOf(0xC1, 0x83, 0x2A, 0x9E, 0x44, 0x00, 0x00, 0x00)

/**
 * The offset a package header of a version the game shipped holds its GUID at.
 */
private const val GUID_OFFSET = 36

/**
 * The size of the header [writePackage] writes.
 */
private const val HEADER_BYTES = 64

/**
 * Writes a package file at [path] whose header holds [guidBytes], creating the directories above it, and returns the
 * path. The header is written out literally, so the GUID it holds is the constant the reader of it is expected to
 * give back.
 */
fun writePackage(
    path: Path,
    guidBytes: ByteArray = GUID_HEADER_BYTES,
): Path {
    path.createParentDirectories()
    path.writeBytes(packageBytes(guidBytes))
    return path
}

/**
 * Returns the bytes of a package whose header holds [guidBytes], for a test serving a package rather than writing one.
 */
fun packageBytes(guidBytes: ByteArray = GUID_HEADER_BYTES): ByteArray {
    val header = ByteArray(HEADER_BYTES)
    TAG_HEADER_BYTES.copyInto(header)
    guidBytes.copyInto(header, GUID_OFFSET)
    return header
}

/**
 * Returns the given [values] as bytes, so that a header is written as the hexadecimal a reader of it sees.
 */
private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
