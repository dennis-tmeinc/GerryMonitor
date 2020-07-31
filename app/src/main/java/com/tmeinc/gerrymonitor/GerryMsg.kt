package com.tmeinc.gerrymonitor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** mss_msg protocol structure
 * defined by Tongrui@TME
 * All values in LSB first(little endian: 0x01234567 = 67 45 23 01).
 * struct mss_msg
 * {
 *    uint16_t id; // magic number: 'M','S' (id[0]='M',id[1]='S')
 *    uint8_t version; // command set version number (currently v.1)
 *    uint8_t command; // command ID of request when ack_code == 0
 *    // command ID of reply when ack_code > 0
 *    uint8_t ack_code; // 0 for request, non-zero for replay(ACK_FAIL, ACK_SUCCESS)
 *    uint8_t reason;   // reason code for fail
 *    uint16_t wData;   // command specific data
 *    uint64_t qwData;  // command specific data
 *    uint32_t dwData;  // command specific data
 *    uint32_t ext_crc; // crc of external data(ext_datasize)
 *    uint32_t ext_datasize; // size of external data following this struct
 *    uint32_t crc;    // crc of this struct excluding this field
 *  };
 **/

class GerryMsg(cmd: Int = CLIENT_KEEPALIVE) {

    companion object {
        // msg structure fields offset
        const val offset_id = 0
        const val offset_id1 = 1
        const val offset_version = 2
        const val offset_command = 3
        const val offset_ack = 4
        const val offset_reason = 5
        const val offset_wData = 6
        const val offset_qwData = 8
        const val offset_dwData = 16
        const val offset_ext_crc = 20
        const val offset_extSize = 24
        const val offset_crc = 28
        const val msg_size = 32

        // gerry commands
        const val CLIENT_LOGIN_REQUEST = 1
        const val CLIENT_LOGIN_PASSWORD = 2
        const val CLIENT_GET_LOC_UNIT_LIST = 3
        const val CLIENT_SUBJECT_STATUS_START = 4
        const val CLIENT_SUBJECT_STATUS_STOP = 5
        const val CLIENT_LIVE_METAVIEW_START = 6
        const val CLIENT_LIVE_METAVIEW_STOP = 7
        const val CLIENT_GET_ALERTS = 8
        const val CLIENT_OPEN_READFILE = 9
        const val CLIENT_READFILE = 10
        const val CLIENT_CLOSE_READFILE = 11
        const val CLIENT_GET_EVENTS = 12
        const val NOTIFY_ALERT = 13
        const val CLIENT_GET_CAM_INFO = 14
        const val CLIENT_KEEPALIVE = 255

        // mdu message
        const val MDU_POSE_DATA = 202
        const val MDU_STATUS_DATA = 205
        const val MDU_EVENT_DATA = 206

        // error code
        const val REASON_NONE = 0
        const val REASON_UNKNOWN_COMMAND = 1
        const val REASON_AUTH_FAIL = 2
        const val REASON_LOGIN_WAIT = 3
        const val REASON_WRONG_FORMAT = 4
        const val REASON_FILE_ACCESS = 5
        const val REASON_SERVER_ACCESS = 6
        const val REASON_NO_DATA = 7
        const val REASON_TIMEOUT = 8
        const val REASON_DUPLICATE_ID = 9
        const val REASON_CHECKSUM = 10

        // ack mode
        const val ACK_NONE = 0
        const val ACK_FAIL = 1
        const val ACK_SUCCESS = 2

    }

    val mssMsg: ByteBuffer = ByteBuffer.allocate(msg_size)
    var xData: ByteBuffer = ByteBuffer.allocate(0)

    init {
        mssMsg.order(ByteOrder.LITTLE_ENDIAN)
        mssMsg.put(offset_id, 'M'.toByte())
        mssMsg.put(offset_id1, 'S'.toByte())
        mssMsg.put(offset_version, 1.toByte())           // version
        mssMsg.put(offset_command, cmd.toByte())         // cmd
        mssMsg.put(offset_ack, 0.toByte())           // request, ask = 0
        mssMsg.putInt(offset_extSize, 0)          // ext_datasize
    }

    constructor(cmd: Int, xml: String?) : this(cmd) {
        setData(xml)
    }

    var command: Int
        get() = mssMsg[offset_command].toInt() and 0xFF
        set(v) {
            mssMsg.put(offset_command, v.toByte())
        }

    var ack: Int
        get() = mssMsg[offset_ack].toInt() and 0xFF
        set(v) {
            mssMsg.put(offset_ack, v.toByte())
        }

    var reason: Int
        get() = mssMsg[offset_reason].toInt() and 0xFF
        set(v) {
            mssMsg.put(offset_reason, v.toByte())
        }

    var wData: Short
        get() = mssMsg.getShort(offset_wData)
        set(v) {
            mssMsg.putShort(offset_wData, v)
        }

    var qwData: Long
        get() = mssMsg.getLong(offset_qwData)
        set(v) {
            mssMsg.putLong(offset_qwData, v)
        }

    var dwData: Int
        get() = mssMsg.getInt(offset_dwData)
        set(v) {
            mssMsg.putInt(offset_dwData, v)
        }

    var extSize: Int
        get() = mssMsg.getInt(offset_extSize)
        set(v) {
            mssMsg.putInt(offset_extSize, v)
        }

    val isValid
        get() = mssMsg[offset_id] == 'M'.toByte() && mssMsg[offset_id1] == 'S'.toByte()

    fun setData(data: String? = null) {
        if (data != null && data.isNotBlank()) {
            xData = ByteBuffer.wrap(data.toByteArray())
        } else {
            xData.limit(0)
        }
        extSize = xData.limit()
    }

    fun setData(data: Map<*, *>) {
        setData(data.toXml())
    }

    // XML data as obj (Map)
    val xmlObj: Any
        get() = String(xData.array(), xData.arrayOffset(), xData.limit()).xmlObj()

    // generate crc checksum
    fun crc() {
        // calc crc
        val crc = CRC32()
        crc.reset()

        val siz = xData.limit()
        if (siz > 0) {
            crc.update(xData.array(), xData.arrayOffset(), siz)
        }
        // save ext crc
        mssMsg.putInt(offset_ext_crc, crc.value.toInt())

        // ext data size
        extSize = siz
        crc.reset()
        crc.update(mssMsg.array(), mssMsg.arrayOffset(), offset_crc)   // exclude crc field itself
        mssMsg.putInt(offset_crc, crc.value.toInt())
    }
}

// status/event icons
/*
enum subject_status {
    STANDING = 1,
    LYING_ON_FLOOR = 2,
    LYING_ON_BED = 3,
    SITTING = 4,
    WALKING = 5,
    FALLEN  = 6,
    ON_BED=7,
    UPBODY_UP_FROM_BED=8,
    FEET_OFF_BED=9,
    SITTING_ON_TOILET=10,
    SITTING_ON_SOFA=11,
    IN_SHOWER=12,
    UNIT_EMPTY=13,
    STANDING_ON_CHAIR=14,
}
*/

val status_icons = arrayOf(
    R.drawable.icon_gerry_event,
    R.drawable.icon_standing,               //    STANDING = 1,
    R.drawable.icon_laying_on_floor,        //    LYING_ON_FLOOR = 2,
    R.drawable.icon_laying_on_bed,          //    LYING_ON_BED = 3,
    R.drawable.icon_sitting,                //    SITTING = 4,
    R.drawable.icon_walking,                //    WALKING = 5,
    R.drawable.icon_falling,                //    FALLEN  = 6,
    R.drawable.icon_laying_on_bed,          //    ON_BED=7,
    R.drawable.icon_in_bed_sitting_up,      //    UPBODY_UP_FROM_BED=8,
    R.drawable.icon_legs_over_bed,          //    FEET_OFF_BED=9,
    R.drawable.icon_toileting,              //    SITTING_ON_TOILET=10,
    R.drawable.icon_sitting_on_sofa,        //    SITTING_ON_SOFA=11,
    R.drawable.icon_showering,              //    IN_SHOWER=12,
    R.drawable.icon_away_not_in_unit,       //    UNIT_EMPTY=13,
    R.drawable.icon_dangerous_maneuver      //    STANDING_ON_CHAIR=14,
)

val status_texts = arrayOf(
    R.string.status_unknown,
    R.string.status_standing,               //    STANDING = 1,
    R.string.status_lying_on_floor,         //    LYING_ON_FLOOR = 2,
    R.string.status_lying_on_bed,           //    LYING_ON_BED = 3,
    R.string.status_sitting,                //    SITTING = 4,
    R.string.status_walking,                //    WALKING = 5,
    R.string.status_fallen,                 //    FALLEN  = 6,
    R.string.status_on_bed,                 //    ON_BED=7,
    R.string.status_upbody_up_from_bed,     //    UPBODY_UP_FROM_BED=8,
    R.string.status_feet_off_bed,           //    FEET_OFF_BED=9,
    R.string.status_sitting_on_toilet,      //    SITTING_ON_TOILET=10,
    R.string.status_sitting_on_sofa,        //    SITTING_ON_SOFA=11,
    R.string.status_in_shower,              //    IN_SHOWER=12,
    R.string.status_unit_empty,             //    UNIT_EMPTY=13,
    R.string.status_standing_on_chair       //    STANDING_ON_CHAIR=14,
)

/*
enum event_type {
  ON_FLOOR = 1,
  ON_FLOOR_NO_MOVEMENT = 2,
  OFF_FLOOR = 3,
  ENTERING  = 4,
  LEAVING   = 5,
  ON_BED    = 6,
  OFF_BED   = 7,
  STANDING  = 8,
  SITTING   = 9,
  WALKING   = 10,
  ENTERING_AT_CONFIGURED_TIME  = 11,
  LEAVING_AT_CONFIGURED_TIME   = 12,
  ACTIVE_AT_NIGHT   = 13,
  FREQUENT_BATH_USE = 14,
  ON_BED_NOT_MOVE_TOO_LONG=15,
  ON_BED_TOO_LONG=16,
  UPPER_BODY_UP_FROM_BED=17,
  STAY_WASHROOM_TOO_LONG=18,
  FEET_OFF_BED=19,
  SITTING_ON_TOILET=20,
  SITTING_ON_SOFA=21,
  ENTER_IN_SHOWER=22,
  ENTER_UNIT=23,
  LEAVE_UNIT=24,
  UNIT_EMPTY=25,
  STANDING_ON_CHAIR=26,
  WANDERING_AT_NIGHT=27,
  STAY_IN_SHOWER_TOO_LONG=28,
  SIT_ON_TOILET_TOO_LONG=29,
  SITTING_ON_SOFA_TOO_LONG=30
}

 */
val event_icons = arrayOf(
    R.drawable.icon_gerry_event,
    R.drawable.icon_laying_on_floor,        //  ON_FLOOR = 1,
    R.drawable.icon_laying_on_floor,        //  ON_FLOOR_NO_MOVEMENT = 2,
    R.drawable.icon_getting_up_from_floor,  //  OFF_FLOOR = 3,
    R.drawable.icon_entering_room,          //  ENTERING  = 4,
    R.drawable.icon_exiting_room,           //  LEAVING   = 5,
    R.drawable.icon_laying_on_bed,          //  ON_BED    = 6,
    R.drawable.icon_getting_out_of_bed,     //  OFF_BED   = 7,
    R.drawable.icon_standing,               //  STANDING  = 8,
    R.drawable.icon_sitting,                //  SITTING   = 9,
    R.drawable.icon_walking,                //  WALKING   = 10,
    R.drawable.icon_entering_unit,          //  ENTERING_AT_CONFIGURED_TIME  = 11,
    R.drawable.icon_exiting_unit,           //  LEAVING_AT_CONFIGURED_TIME   = 12,
    R.drawable.icon_wandering,              //  ACTIVE_AT_NIGHT   = 13,
    R.drawable.icon_showering,              //  FREQUENT_BATH_USE = 14,
    R.drawable.icon_in_bed_too_long,        //  ON_BED_NOT_MOVE_TOO_LONG=15,
    R.drawable.icon_in_bed_too_long,        //  ON_BED_TOO_LONG=16,
    R.drawable.icon_in_bed_sitting_up,      //  UPPER_BODY_UP_FROM_BED=17,
    R.drawable.icon_in_washroom_too_long,   //  STAY_WASHROOM_TOO_LONG=18,
    R.drawable.icon_legs_over_bed,          //  FEET_OFF_BED=19,
    R.drawable.icon_toileting,              //  SITTING_ON_TOILET=20,
    R.drawable.icon_sitting_on_sofa,        //  SITTING_ON_SOFA=21,
    R.drawable.icon_showering,              //  ENTER_IN_SHOWER=22,
    R.drawable.icon_entering_unit,          //  ENTER_UNIT=23,
    R.drawable.icon_exiting_unit,           //  LEAVE_UNIT=24,
    R.drawable.icon_away_not_in_unit,       //  UNIT_EMPTY=25,
    R.drawable.icon_dangerous_maneuver,     //  STANDING_ON_CHAIR=26,
    R.drawable.icon_wandering,              //  WANDERING_AT_NIGHT=27,
    R.drawable.icon_showering,              //  STAY_IN_SHOWER_TOO_LONG=28,
    R.drawable.icon_toileting,              //  SIT_ON_TOILET_TOO_LONG=29,
    R.drawable.icon_sitting_on_sofa         //  SITTING_ON_SOFA_TOO_LONG=30
)

val event_texts = arrayOf(
    R.string.event_unknown,
    R.string.event_on_floor,                        //  ON_FLOOR = 1,
    R.string.event_on_floor_no_movement,            //  ON_FLOOR_NO_MOVEMENT = 2,
    R.string.event_off_floor,                       //  OFF_FLOOR = 3,
    R.string.event_entering,                        //  ENTERING  = 4,
    R.string.event_leaving,                         //  LEAVING   = 5,
    R.string.event_on_bed,                          //  ON_BED    = 6,
    R.string.event_off_bed,                         //  OFF_BED   = 7,
    R.string.event_standing,                        //  STANDING  = 8,
    R.string.event_sitting,                         //  SITTING   = 9,
    R.string.event_walking,                         //  WALKING   = 10,
    R.string.event_entering_at_configured_time,     //  ENTERING_AT_CONFIGURED_TIME  = 11,
    R.string.event_leaving_at_configured_time,      //  LEAVING_AT_CONFIGURED_TIME   = 12,
    R.string.event_active_at_night,                 //  ACTIVE_AT_NIGHT   = 13,
    R.string.event_frequent_bath_use,               //  FREQUENT_BATH_USE = 14,
    R.string.event_on_bed_not_move_too_long,        //  ON_BED_NOT_MOVE_TOO_LONG=15,
    R.string.event_on_bed_too_long,                 //  ON_BED_TOO_LONG=16,
    R.string.event_upper_body_up_from_bed,          //  UPPER_BODY_UP_FROM_BED=17,
    R.string.event_stay_washroom_too_long,          //  STAY_WASHROOM_TOO_LONG=18,
    R.string.event_feet_off_bed,                    //  FEET_OFF_BED=19,
    R.string.event_sitting_on_toilet,               //  SITTING_ON_TOILET=20,
    R.string.event_sitting_on_sofa,                 //  SITTING_ON_SOFA=21,
    R.string.event_enter_in_shower,                 //  ENTER_IN_SHOWER=22,
    R.string.event_enter_unit,                      //  ENTER_UNIT=23,
    R.string.event_leave_unit,                      //  LEAVE_UNIT=24,
    R.string.event_unit_empty,                      //  UNIT_EMPTY=25,
    R.string.event_standing_on_chair,               //  STANDING_ON_CHAIR=26,
    R.string.event_wandering_at_night,              //  WANDERING_AT_NIGHT=27,
    R.string.event_stay_in_shower_too_long,         //  STAY_IN_SHOWER_TOO_LONG=28,
    R.string.event_sit_on_toilet_too_long,          //  SIT_ON_TOILET_TOO_LONG=29,
    R.string.event_sitting_on_sofa                  //  SITTING_ON_SOFA_TOO_LONG=30
)