package com.tmeinc.gerrymonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * fragment for status list
 */
class StatusFragment : Fragment() {

    class UnitStatus(val mduId: String) {

        private val mdu: Any?
            get() =
                synchronized(GerryService.gerryMDUs) {
                    objGetLeaf(GerryService.gerryMDUs, mduId)
                }

        val isReady: Boolean
            get() = objGetLeafString(mdu, "status") == "Run"

        val location: String
            get() = objGetLeafString(mdu, "info/loc")

        val unit: String
            get() = objGetLeafString(mdu, "info/unit")

        val subs: Int
            get() = objGetLeafInt(mdu, "status_mdup/unitsub")

        val rooms: List<Any?>
            get() = objGetLeafArray(mdu, "status_mdup/rooms/room")

    }

    var statusList = mutableListOf<UnitStatus>()   // display alert list

    inner class StatusListAdapter() : RecyclerView.Adapter<StatusListAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_status, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val us = statusList[position]

            holder.location.text = "Location: ${us.location}"
            holder.unit.text = "Unit: ${us.unit}"

            val subTotal = us.subs
            if (!us.isReady) {
                holder.subs_icon.visibility = View.GONE
                holder.subs_text.text = "Device not ready!"
            } else if (subTotal <= 0) {
                holder.subs_icon.visibility = View.VISIBLE
                holder.subs_text.text = "0 occupant"
            } else if (subTotal == 1) {
                holder.subs_icon.visibility = View.GONE
                holder.subs_text.text = "1 occupant"
            } else {  // > 1
                holder.subs_icon.visibility = View.GONE
                holder.subs_text.text = "$subTotal occupants"
            }

            //  bind all subs
            holder.tableLayout.removeAllViews()

            var subjectCount = 0
            var tableRow = TableRow(context)
            for (r in us.rooms) {
                if (r is Map<*, *>) {
                    val roomName = objGetLeafString(r, "name")
                    val subList = objGetLeafArray(r, "subs/sub")
                    for (sub in subList ) {
                        if (sub is String) {
                            if (subjectCount++ % 2 == 0) {
                                tableRow = TableRow(context)
                                holder.tableLayout.addView(tableRow)
                            }
                            val statusSub = LayoutInflater.from(context)
                                .inflate(
                                    R.layout.status_subject,
                                    tableRow,
                                    false
                                ) as LinearLayout

                            val subSplit = sub.split(",")
                            (statusSub.findViewById(R.id.roomName) as TextView).text =
                                "Room: ${roomName}"

                            var si = subSplit[0].toInt()
                            if( si<0 || si>=status_icons.size)
                                si=0
                            (statusSub.findViewById(R.id.statusIcon) as ImageView).setImageResource(
                                status_icons[si]
                            )
                            (statusSub.findViewById(R.id.statusText) as TextView).setText(status_texts[si])

                            if (subSplit.count() > 2) {
                                try {
                                    val datetime =
                                        SimpleDateFormat("yyyyMMddHHmmss").parse(subSplit[2])?: Date()
                                    val datetimeStr = SimpleDateFormat.getDateTimeInstance(
                                        DateFormat.DEFAULT,
                                        DateFormat.DEFAULT
                                    ).format(datetime)
                                    (statusSub.findViewById(R.id.statusTime) as TextView).text =
                                        datetimeStr
                                } catch (pe: Exception) {
                                    (statusSub.findViewById(R.id.statusTime) as View).visibility =
                                        View.GONE
                                }
                            } else
                                (statusSub.findViewById(R.id.statusTime) as View).visibility =
                                    View.GONE
                            tableRow.addView(statusSub)
                        }
                    }
                }
            }
            holder.tableLayout.requestLayout()
        }

        override fun getItemCount(): Int = statusList.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            init {
                val backgrondcolor = listOf(
                    R.color.material_on_background_emphasis_high_type,
                    R.color.colorAccent
                )
                view.setBackgroundResource(backgrondcolor[1])
            }

            val location: TextView = view.findViewById(R.id.location)
            val unit: TextView = view.findViewById(R.id.unit)
            val subs_icon: ImageView = view.findViewById(R.id.subs_icon)
            val subs_text: TextView = view.findViewById(R.id.subs_text)
            val tableLayout: TableLayout = view.findViewById(R.id.statusTableLayout)
        }
    }

    val statusListAdapter = StatusListAdapter()

    // status update callback
    fun statusCB(u: UnitStatus) {
        val pos = statusList.indexOf(u)
        if (pos >= 0) {
            statusListAdapter.notifyItemChanged(pos)
        }
    }

    fun startStatus() {
        statusList.clear()
        synchronized(GerryService.gerryMDUs) {
            for (mdu in GerryService.gerryMDUs.keys) {
                statusList.add(UnitStatus(mdu))
            }
        }
        for (s in statusList) {
            val obj = mapOf(
                "mdu" to s.mduId,
                "callback" to {
                    statusCB(s)
                }
            )
            GerryService.instance
                ?.gerryHandler
                ?.obtainMessage(GerryService.MSG_GERRY_STATUS_START, obj)
                ?.sendToTarget()
        }
        statusListAdapter.notifyDataSetChanged()
    }

    fun stopStatus() {
        GerryService.instance
            ?.gerryHandler
            ?.sendEmptyMessage(GerryService.MSG_GERRY_STATUS_STOP)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_status_list, container, false)

        // Set the adapter
        if (view is RecyclerView) {
            val layoutManager = LinearLayoutManager(context)
            view.layoutManager = layoutManager
            view.adapter = statusListAdapter

            val itemDecoration = DividerItemDecoration(
                view.context,
                layoutManager.getOrientation()
            )
            view.addItemDecoration(itemDecoration)
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        startStatus()
    }

    override fun onStop() {
        super.onStop()
        stopStatus()
    }

}