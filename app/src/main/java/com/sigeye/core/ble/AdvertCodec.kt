package com.sigeye.core.ble

/**
 * One advertisement as a line of text, and back again.
 *
 * The point of this file is that a bug which only happens on a train can currently only be
 * debugged on that train. Everything in the app consumes the live radio, so a wrong reading
 * in a cafe is gone the moment you leave it - and the person who can reproduce it is never
 * the person who can fix it.
 *
 * A capture fixes that. Record the raw stream to a file, replay it at a desk, and the bug
 * happens again on demand. It also gives the analysis classes something they have never
 * had: real input. Every test in this app builds its own synthetic packets, which proves
 * the arithmetic and proves nothing about what a real room looks like.
 *
 * Deliberately a text format rather than anything clever. A capture that cannot be opened
 * in a text editor is a capture nobody will look at, and the whole value here is that
 * somebody else can look.
 *
 * Pure and Android-free.
 */
object AdvertCodec {

    const val VERSION = 1

    /**
     * Column order, which is also the file's header line.
     *
     * Times are written relative to the start of the capture rather than as wall clock.
     * A capture replayed six months later would otherwise claim every packet arrived in
     * the past, and half the app decides whether something is present by comparing a
     * timestamp to now.
     */
    const val HEADER = "t_ms,address,rssi,name,company,mfg,services,service_data," +
        "tx,appearance,legacy,connectable,phy1,phy2,sid"

    fun encode(advert: Advert, startMs: Long): String = listOf(
        (advert.atMs - startMs).toString(),
        advert.address,
        advert.rssi.toString(),
        escape(advert.name),
        advert.companyId?.toString() ?: "",
        advert.manufacturerData?.let { hex(it) } ?: "",
        advert.serviceUuids.joinToString(" "),
        advert.serviceData.entries.joinToString(" ") { "${it.key}:${hex(it.value)}" },
        advert.txPower?.toString() ?: "",
        advert.appearance?.toString() ?: "",
        if (advert.isLegacy) "1" else "0",
        if (advert.isConnectable) "1" else "0",
        advert.primaryPhy.toString(),
        advert.secondaryPhy.toString(),
        advert.advertisingSid.toString(),
    ).joinToString(",")

    /**
     * @param baseMs what the start of the capture should be replayed as - normally now.
     * @return null for a header, a comment, a blank line or anything malformed. A capture
     *   with one bad line in it is still worth replaying, and throwing would throw the
     *   whole afternoon away.
     */
    fun decode(line: String, baseMs: Long): Advert? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("t_ms")) {
            return null
        }
        val parts = trimmed.split(",")
        if (parts.size < 15) return null
        val offset = parts[0].toLongOrNull() ?: return null
        val address = parts[1].takeIf { it.isNotBlank() } ?: return null
        val rssi = parts[2].toIntOrNull() ?: return null

        return Advert(
            address = address,
            rssi = rssi,
            atMs = baseMs + offset,
            name = unescape(parts[3]),
            companyId = parts[4].toIntOrNull(),
            manufacturerData = parts[5].takeIf { it.isNotBlank() }?.let { unhex(it) },
            serviceUuids = parts[6].split(" ").filter { it.isNotBlank() },
            serviceData = parts[7].split(" ")
                .filter { it.contains(":") }
                .mapNotNull { entry ->
                    val key = entry.substringBefore(":")
                    val value = unhex(entry.substringAfter(":"))
                    if (key.isBlank() || value == null) null else key to value
                }
                .toMap(),
            txPower = parts[8].toIntOrNull(),
            appearance = parts[9].toIntOrNull(),
            isLegacy = parts[10] != "0",
            isConnectable = parts[11] == "1",
            primaryPhy = parts[12].toIntOrNull() ?: 1,
            secondaryPhy = parts[13].toIntOrNull() ?: 0,
            advertisingSid = parts[14].toIntOrNull() ?: 0xFF,
        )
    }

    /** How long a capture covers, from its first and last offsets. */
    fun spanMs(lines: List<String>): Long {
        val offsets = lines.mapNotNull { line ->
            line.trim().takeIf {
                it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("t_ms")
            }?.substringBefore(",")?.toLongOrNull()
        }
        if (offsets.isEmpty()) return 0L
        return offsets.max() - offsets.min()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return runCatching {
            ByteArray(text.length / 2) { index ->
                text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    /**
     * Device names contain commas, and one of them would silently shift every column after
     * it - which is the sort of thing that corrupts a capture without anybody noticing
     * until the replay makes no sense.
     */
    private fun escape(name: String?): String = name.orEmpty()
        .replace("\\", "\\\\")
        .replace(",", "\\c")
        .replace("\n", " ")

    private fun unescape(text: String): String? = text
        .takeIf { it.isNotBlank() }
        ?.replace("\\c", ",")
        ?.replace("\\\\", "\\")
}
