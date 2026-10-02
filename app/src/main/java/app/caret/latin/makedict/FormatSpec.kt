/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package app.caret.latin.makedict

import app.caret.latin.define.DecoderSpecificConstants

import java.util.Date
import java.util.HashMap

/**
 * Dictionary File Format Specification.
 */
object FormatSpec {

    /*
     * File header layout is as follows:
     *
     * v |
     * e | MAGIC_NUMBER + version of the file format, 2 bytes.
     * r |
     * sion
     *
     * o |
     * p | not used, 2 bytes.
     * o |
     * nflags
     *
     * h |
     * e | size of the file header, 4bytes
     * a |   including the size of the magic number, the option flags and the header size
     * d |
     * ersize
     *
     * attributes list
     *
     * attributes list is:
     * <key>   = | string of characters at the char format described below, with the terminator used
     *           | to signal the end of the string.
     * <value> = | string of characters at the char format described below, with the terminator used
     *           | to signal the end of the string.
     * if the size of already read < headersize, goto key.
     *
     */

    /*
     * Node array (FusionDictionary.PtNodeArray) layout is as follows:
     *
     * n |
     * o | the number of PtNodes, 1 or 2 bytes.
     * d | 1 byte = bbbbbbbb match
     * e |   case 1xxxxxxx => xxxxxxx << 8 + next byte
     * c |   otherwise => bbbbbbbb
     * o |
     * unt
     *
     * n |
     * o | sequence of PtNodes,
     * d | the layout of each PtNode is described below.
     * e |
     * s
     *
     * f |
     * o | forward link address, 3byte
     * r | 1 byte = bbbbbbbb match
     * w |   case 1xxxxxxx => -((xxxxxxx << 16) + (next byte << 8) + next byte)
     * a |   otherwise => (xxxxxxx << 16) + (next byte << 8) + next byte
     * r |
     * dlinkaddress
     */

    /* Node (FusionDictionary.PtNode) layout is as follows:
     *   | CHILDREN_ADDRESS_TYPE  2 bits, 11          : FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES
     *   |                                10          : FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES
     * f |                                01          : FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE
     * l |                                00          : FLAG_CHILDREN_ADDRESS_TYPE_NOADDRESS
     * a | has several chars ?         1 bit, 1 = yes, 0 = no   : FLAG_HAS_MULTIPLE_CHARS
     * g | has a terminal ?            1 bit, 1 = yes, 0 = no   : FLAG_IS_TERMINAL
     * s | has shortcut targets ?      1 bit, 1 = yes, 0 = no   : FLAG_HAS_SHORTCUT_TARGETS
     *   | has bigrams ?               1 bit, 1 = yes, 0 = no   : FLAG_HAS_BIGRAMS
     *   | is not a word ?             1 bit, 1 = yes, 0 = no   : FLAG_IS_NOT_A_WORD
     *   | is possibly offensive ?     1 bit, 1 = yes, 0 = no   : FLAG_IS_POSSIBLY_OFFENSIVE
     *
     * c | IF FLAG_HAS_MULTIPLE_CHARS
     * h |   char, char, char, char    n * (1 or 3 bytes) : use PtNodeInfo for i/o helpers
     * a |   end                       1 byte, = 0
     * r | ELSE
     * s |   char                      1 or 3 bytes
     *   | END
     *
     * f |
     * r | IF FLAG_IS_TERMINAL
     * e |   frequency                 1 byte
     * q |
     *
     * c |
     * h | children address, CHILDREN_ADDRESS_TYPE bytes
     * i | This address is relative to the position of this field.
     * l |
     * drenaddress
     *
     *   | IF FLAG_IS_TERMINAL && FLAG_HAS_SHORTCUT_TARGETS
     *   | shortcut string list
     *   | IF FLAG_IS_TERMINAL && FLAG_HAS_BIGRAMS
     *   | bigrams address list
     *
     * Char format is:
     * 1 byte = bbbbbbbb match
     * case 000xxxxx: xxxxx << 16 + next byte << 8 + next byte
     * else: if 00011111 (= 0x1F) : this is the terminator. This is a relevant choice because
     *       unicode code points range from 0 to 0x10FFFF, so any 3-byte value starting with
     *       00011111 would be outside unicode.
     * else: iso-latin-1 code
     * This allows for the whole unicode range to be encoded, including chars outside of
     * the BMP. Also everything in the iso-latin-1 charset is only 1 byte, except control
     * characters which should never happen anyway (and still work, but take 3 bytes).
     *
     * bigram address list is:
     * <flags> = | hasNext = 1 bit, 1 = yes, 0 = no     : FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT
     *           | addressSign = 1 bit,                 : FLAG_BIGRAM_ATTR_OFFSET_NEGATIVE
     *           |                      1 = must take -address, 0 = must take +address
     *           |                         xx : mask with MASK_BIGRAM_ATTR_ADDRESS_TYPE
     *           | addressFormat = 2 bits, 00 = unused  : FLAG_BIGRAM_ATTR_ADDRESS_TYPE_ONEBYTE
     *           |                         01 = 1 byte  : FLAG_BIGRAM_ATTR_ADDRESS_TYPE_ONEBYTE
     *           |                         10 = 2 bytes : FLAG_BIGRAM_ATTR_ADDRESS_TYPE_TWOBYTES
     *           |                         11 = 3 bytes : FLAG_BIGRAM_ATTR_ADDRESS_TYPE_THREEBYTES
     *           | 4 bits : frequency         : mask with FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY
     * <address> | IF (01 == FLAG_BIGRAM_ATTR_ADDRESS_TYPE_ONEBYTE == addressFormat)
     *           |   read 1 byte, add top 4 bits
     *           | ELSIF (10 == FLAG_BIGRAM_ATTR_ADDRESS_TYPE_TWOBYTES == addressFormat)
     *           |   read 2 bytes, add top 4 bits
     *           | ELSE // 11 == FLAG_BIGRAM_ATTR_ADDRESS_TYPE_THREEBYTES == addressFormat
     *           |   read 3 bytes, add top 4 bits
     *           | END
     *           | if (FLAG_BIGRAM_ATTR_OFFSET_NEGATIVE) then address = -address
     * if (FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT) goto bigram_and_shortcut_address_list_is
     *
     * shortcut string list is:
     * <byte size> = PTNODE_SHORTCUT_LIST_SIZE_SIZE bytes, big-endian: size of the list, in bytes.
     * <flags>     = | hasNext = 1 bit, 1 = yes, 0 = no : FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT
     *               | reserved = 3 bits, must be 0
     *               | 4 bits : frequency : mask with FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY
     * <shortcut>  = | string of characters at the char format described above, with the terminator
     *               | used to signal the end of the string.
     * if (FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT goto flags
     */

    const val MAGIC_NUMBER = 0x9BC13AFE.toInt()
    const val NOT_A_VERSION_NUMBER = -1

    // These MUST have the same values as the relevant constants in format_utils.h.
    // From version 2.01 on, we use version * 100 + revision as a version number. That allows
    // us to change the format during development while having testing devices remove
    // older files with each upgrade, while still having a readable versioning scheme.
    // When we bump up the dictionary format version, we should update
    // ExpandableDictionary.needsToMigrateDictionary() and
    // ExpandableDictionary.matchesExpectedBinaryDictFormatVersionForThisType().
    const val VERSION2 = 2
    const val VERSION201 = 201
    const val VERSION202 = 202
    // format version for Fava Dictionaries.
    const val VERSION_DELIGHT3 = 86736212
    const val MINIMUM_SUPPORTED_VERSION_OF_CODE_POINT_TABLE = VERSION201
    // Dictionary version used for testing.
    const val VERSION4_ONLY_FOR_TESTING = 399
    const val VERSION402 = 402
    const val VERSION403 = 403
    const val VERSION4 = VERSION403
    const val MINIMUM_SUPPORTED_STATIC_VERSION = VERSION202
    const val MAXIMUM_SUPPORTED_STATIC_VERSION = VERSION_DELIGHT3
    const val MINIMUM_SUPPORTED_DYNAMIC_VERSION = VERSION4
    const val MAXIMUM_SUPPORTED_DYNAMIC_VERSION = VERSION403

    // TODO: Make this value adaptative to content data, store it in the header, and
    // use it in the reading code.
    const val MAX_WORD_LENGTH = DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH

    // These flags are used only in the static dictionary.
    const val MASK_CHILDREN_ADDRESS_TYPE = 0xC0
    const val FLAG_CHILDREN_ADDRESS_TYPE_NOADDRESS = 0x00
    const val FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE = 0x40
    const val FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES = 0x80
    const val FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES = 0xC0

    const val FLAG_HAS_MULTIPLE_CHARS = 0x20

    const val FLAG_IS_TERMINAL = 0x10
    const val FLAG_HAS_SHORTCUT_TARGETS = 0x08
    const val FLAG_HAS_BIGRAMS = 0x04
    const val FLAG_IS_NOT_A_WORD = 0x02
    const val FLAG_IS_POSSIBLY_OFFENSIVE = 0x01

    const val FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT = 0x80
    const val FLAG_BIGRAM_ATTR_OFFSET_NEGATIVE = 0x40
    const val MASK_BIGRAM_ATTR_ADDRESS_TYPE = 0x30
    const val FLAG_BIGRAM_ATTR_ADDRESS_TYPE_ONEBYTE = 0x10
    const val FLAG_BIGRAM_ATTR_ADDRESS_TYPE_TWOBYTES = 0x20
    const val FLAG_BIGRAM_ATTR_ADDRESS_TYPE_THREEBYTES = 0x30
    const val FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY = 0x0F

    const val PTNODE_CHARACTERS_TERMINATOR = 0x1F

    const val PTNODE_TERMINATOR_SIZE = 1
    const val PTNODE_FLAGS_SIZE = 1
    const val PTNODE_FREQUENCY_SIZE = 1
    const val PTNODE_MAX_ADDRESS_SIZE = 3
    const val PTNODE_ATTRIBUTE_FLAGS_SIZE = 1
    const val PTNODE_ATTRIBUTE_MAX_ADDRESS_SIZE = 3
    const val PTNODE_SHORTCUT_LIST_SIZE_SIZE = 2

    const val NO_CHILDREN_ADDRESS = Int.MIN_VALUE
    const val INVALID_CHARACTER = -1

    const val MAX_PTNODES_FOR_ONE_BYTE_PTNODE_COUNT = 0x7F // 127
    // Large PtNode array size field size is 2 bytes.
    const val LARGE_PTNODE_ARRAY_SIZE_FIELD_SIZE_FLAG = 0x8000
    const val MAX_PTNODES_IN_A_PT_NODE_ARRAY = 0x7FFF // 32767
    const val MAX_BIGRAMS_IN_A_PTNODE = 10000
    const val MAX_SHORTCUT_LIST_SIZE_IN_A_PTNODE = 0xFFFF

    const val MAX_TERMINAL_FREQUENCY = 255
    const val MAX_BIGRAM_FREQUENCY = 15

    const val SHORTCUT_WHITELIST_FREQUENCY = 15

    // This option needs to be the same numeric value as the one in binary_format.h.
    const val NOT_VALID_WORD = -99

    const val UINT8_MAX = 0xFF
    const val UINT16_MAX = 0xFFFF
    const val UINT24_MAX = 0xFFFFFF
    const val MSB8 = 0x80
    const val MINIMAL_ONE_BYTE_CHARACTER_VALUE = 0x20
    const val MAXIMAL_ONE_BYTE_CHARACTER_VALUE = 0xFF

    /**
     * Options global to the dictionary.
     */
    class DictionaryOptions(attributes: HashMap<String, String>) {
        @JvmField val mAttributes: HashMap<String, String>
        init {
            mAttributes = attributes
        }
        override fun toString(): String { // Convenience method
            return toString(0, false)
        }
        fun toString(indentCount: Int, plumbing: Boolean): String {
            val indent = StringBuilder()
            if (plumbing) {
                indent.append("H:")
            } else {
                for (i in 0 until indentCount) {
                    indent.append(" ")
                }
            }
            val s = StringBuilder()
            for (optionKey in mAttributes.keys) {
                s.append(indent)
                s.append(optionKey)
                s.append(" = ")
                if ("date" == optionKey && !plumbing) {
                    // Date needs a number of milliseconds, but the dictionary contains seconds
                    s.append(Date(1000 * java.lang.Long.parseLong(mAttributes[optionKey])))
                } else {
                    s.append(mAttributes[optionKey])
                }
                s.append("\n")
            }
            return s.toString()
        }
    }

    // This utility class is not publicly instantiable.
}
