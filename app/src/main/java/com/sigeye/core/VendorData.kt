package com.sigeye.core

/**
 * Vendor tables, generated from the IEEE OUI registry and the Bluetooth SIG company
 * identifier list rather than typed by hand - a hand-written version had five of
 * twenty-three company IDs wrong, which in an app that exists to identify things is worse
 * than having none at all. Each entry carries the registry name it was checked against.
 *
 * Curated rather than complete: the full IEEE file is nearly four megabytes, and an
 * unrecognised prefix is shown as raw hex anyway.
 */
internal object VendorData {

    /** First three bytes of a MAC, assigned by the IEEE. */
    val OUI: Map<String, String> = mapOf(
        "00:04:7D" to "Motorola Solutions", // registry: Motorola Solutions Inc.
        "00:06:33" to "HID Global / Crossmatch", // registry: Crossmatch Technologies/HID Global
        "00:06:8E" to "HID Corporation", // registry: HID Corporation
        "00:17:7A" to "ASSA ABLOY", // registry: ASSA ABLOY AB
        "00:17:88" to "Philips Hue", // registry: Philips Lighting BV
        "00:1F:92" to "Motorola Solutions", // registry: Motorola Solutions Inc.
        "00:25:DF" to "Axon Enterprise", // registry: Axon Enterprise, Inc.
        "00:30:8E" to "HID Global / Crossmatch", // registry: Crossmatch Technologies/HID Global
        "0C:BF:15" to "Genetec", // registry: Genetec Inc.
        "10:74:6F" to "Motorola Solutions", // registry: MOTOROLA SOLUTIONS MALAYSIA SDN. BHD.
        "24:0A:C4" to "Espressif", // registry: Espressif Inc.
        "3C:5A:B4" to "Google", // registry: Google, Inc.
        "54:E0:19" to "Ring", // registry: Ring LLC
        "70:1A:D5" to "Avigilon Alta", // registry: Avigilon Alta
        "7C:DF:A1" to "Espressif", // registry: Espressif Inc.
        "84:EB:3F" to "Vivint", // registry: Vivint Inc
        "AC:9F:C3" to "Ring", // registry: Ring LLC
        "B0:44:9C" to "ASSA ABLOY (Yale)", // registry: Assa Abloy AB - Yale
        "B4:1E:52" to "Flock Safety", // registry: Flock Safety
        "B8:27:EB" to "Raspberry Pi", // registry: Raspberry Pi Foundation
        "CC:3B:FB" to "Ring", // registry: Ring LLC
        "DC:A6:32" to "Raspberry Pi", // registry: Raspberry Pi Trading Ltd
        "E0:A7:00" to "Verkada", // registry: Verkada Inc
        "E4:5F:01" to "Raspberry Pi", // registry: Raspberry Pi Trading Ltd
        "EC:FA:BC" to "Espressif", // registry: Espressif Inc.
        "F4:F5:D8" to "Google", // registry: Google, Inc.
        "00:58:28" to "Axon Networks Inc. (not Axon Enterprise)",
        "84:70:03" to "Axon Networks Inc. (not Axon Enterprise)",
        "00:C0:D4" to "AXON NETWORKS, INC. (not Axon Enterprise)",
    )

    /** 16-bit identifiers assigned by the Bluetooth SIG, carried in manufacturer data. */
    val COMPANY: Map<Int, String> = mapOf(
        0x0001 to "Nokia", // registry: Nokia Mobile Phones
        0x0002 to "Intel", // registry: Intel Corp.
        0x0006 to "Microsoft", // registry: Microsoft
        0x000D to "Texas Instruments", // registry: Texas Instruments Inc.
        0x000F to "Broadcom", // registry: Broadcom Corporation
        0x0030 to "ST Microelectronics", // registry: ST Microelectronics
        0x004C to "Apple", // registry: Apple, Inc.
        0x0059 to "Nordic Semiconductor", // registry: Nordic Semiconductor ASA
        0x0075 to "Samsung", // registry: Samsung Electronics Co. Ltd.
        0x0087 to "Garmin", // registry: Garmin International, Inc.
        0x009E to "Bose", // registry: Bose Corporation
        0x00E0 to "Google", // registry: Google
        0x0110 to "Nippon Seiki", // registry: Nippon Seiki Co., Ltd.
        0x0118 to "Radius Networks", // registry: Radius Networks, Inc.
        0x0124 to "HID Global", // registry: HID Global
        0x012E to "ASSA ABLOY", // registry: ASSA ABLOY
        0x0131 to "Cypress", // registry: Cypress Semiconductor
        0x0154 to "Pebble", // registry: Pebble Technology
        0x0157 to "Huami / Amazfit", // registry: Anhui Huami Information Technology Co., Ltd.
        0x0171 to "Amazon", // registry: Amazon.com Services LLC
        0x02E5 to "Espressif", // registry: Espressif Systems (Shanghai) Co., Ltd.
        0x038F to "Xiaomi", // registry: Xiaomi Inc.
        0x03FF to "Withings", // registry: Withings
        0x0499 to "Ruuvi", // registry: Ruuvi Innovations Ltd.
        0x0553 to "Nintendo", // registry: Nintendo Co., Ltd.
        0x05A7 to "Sonos", // registry: Sonos Inc
        0x067C to "Tile", // registry: Tile, Inc.
        0x09C8 to "XUNTONG (Flock battery)", // registry: XUNTONG
    )
}
