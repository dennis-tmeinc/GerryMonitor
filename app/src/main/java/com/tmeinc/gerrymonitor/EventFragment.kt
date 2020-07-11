package com.tmeinc.gerrymonitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*


/**
 * A fragment representing a list of Events/Alerts
 */
class EventFragment(val type: String = ALERT_LIST) : Fragment() {

    companion object {
        const val EVENT_LIST = "events"
        const val ALERT_LIST = "alerts"
    }

    class EventItem(val event: Map<*, *>) : Comparable<EventItem> {

        val mdu = objGetLeafString(event, "mdu")
        val ts = objGetLeafLong(event, "ts")
        val room = objGetLeafString(event, "room")
        val type = objGetLeafInt(event, "type")
        val path = objGetLeafString(event, "path")

        val loc: String
            get() {
                val gerryUnit =
                    synchronized(GerryService.gerryMDUs) {
                        GerryService.gerryMDUs[mdu]
                    }
                return objGetLeafString(gerryUnit, "info/loc")
            }

        val unit: String
            get() {
                val gerryUnit =
                    synchronized(GerryService.gerryMDUs) {
                        GerryService.gerryMDUs[mdu]
                    }
                return objGetLeafString(gerryUnit, "info/unit")
            }

        val resident: String
            get() {
                val gerryUnit =
                    synchronized(GerryService.gerryMDUs) {
                        GerryService.gerryMDUs[mdu]
                    }
                val res = objGetLeafArray(gerryUnit, "info/residents/name")
                return res.joinToString {
                    it.toString()
                }
            }

        val time: String
            get() {
                val date = Date( 1000L * ts)
                return SimpleDateFormat.getDateTimeInstance(
                    DateFormat.DEFAULT,
                    DateFormat.DEFAULT
                ).format(date)
            }

        override fun compareTo(other: EventItem): Int {
            var comp = ts.compareTo(other.ts)
            if (comp == 0) {
                comp = mdu.compareTo(other.mdu)
            }
            return comp
        }

    }

    var displayList = listOf<EventItem>()   // display alert list

    inner class EventListAdapter() : RecyclerView.Adapter<EventListAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_event, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayList[position]

            holder.icon.setImageResource(event_icons[item.type])
            holder.name.setText(event_texts[item.type])
            holder.unit.text = "${item.loc} , ${item.unit}"
            if (item.resident.isBlank()) {
                holder.residents.visibility = View.GONE
            } else {
                holder.residents.text = item.resident
            }
            holder.room.text = item.room
            holder.time.text = item.time
        }

        override fun getItemCount(): Int = displayList.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.event_icon)
            val name: TextView = view.findViewById(R.id.event_name)
            val unit: TextView = view.findViewById(R.id.unit)
            val residents: TextView = view.findViewById(R.id.residents)
            val room: TextView = view.findViewById(R.id.room)
            val time: TextView = view.findViewById(R.id.time)
        }
    }

    val displayAdapter = EventListAdapter()

    fun listCB(events: List<Any?>) {
        val eventList = mutableListOf<EventItem>()
        for (e in events) {
            if (e is Map<*, *>)
                eventList.add(
                    EventItem(e)
                )
        }
        // do filter and sort here
        eventList.sortDescending()

        /*
        eventList.sortWith(Comparator<EventItem> { o1, o2 ->
            (o2.tm.toLong() - o1.tm.toLong()).toInt()
        })
         */

        displayList = eventList.toList()
        displayAdapter.notifyDataSetChanged()
    }

    fun updateList() {

        var getListCommand = GerryMsg.CLIENT_GET_EVENTS
        if (type == ALERT_LIST) {
            getListCommand = GerryMsg.CLIENT_GET_ALERTS
        }
        val obj = mapOf(
            "days" to 30,
            "command" to getListCommand,
            "callback" to { list: List<Any?> ->
                listCB(list)
            }
        )
        GerryService.instance
            ?.gerryHandler
            ?.obtainMessage(GerryService.MSG_GERRY_GET_EVENTS, obj)
            ?.sendToTarget()

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_event_list, container, false)

        // Set the adapter
        if (view is RecyclerView) {
            val layoutManager = LinearLayoutManager(context)
            view.layoutManager = layoutManager
            view.adapter = displayAdapter

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
        updateList()
    }
}