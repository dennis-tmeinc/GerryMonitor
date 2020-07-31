package com.tmeinc.gerrymonitor

import android.content.Intent
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

    class EventItem(val mdu: String, val event: Any?) : Comparable<EventItem> {

        val ts = event.getLeafLong("ts")
        val room = event.getLeafString("room")
        val type = event.getLeafInt("type")
        val eventFile = event.getLeafString("path")
        val status = event.getLeafInt("status")
        val staff = event.getLeafInt("staff")
        val cameras = event.getLeafArray("camera")

        private val gerryUnit = synchronized(GerryService.gerryMDUs) {
            GerryService.gerryMDUs[mdu]
        }

        val loc = gerryUnit.getLeafString("info/loc")

        val unit = gerryUnit.getLeafString("info/unit")

        val resident: String
            get() = gerryUnit.getLeafArray("info/residents/name").joinToString {
                it.toString()
            }

        val time: String
            get() = SimpleDateFormat.getDateTimeInstance(
                DateFormat.DEFAULT,
                DateFormat.DEFAULT
            ).format(Date(1000L * ts))

        override fun compareTo(other: EventItem): Int {
            return ts.compareTo(other.ts)
        }

    }

    private var eventListChanged = false
    private val eventList = mutableListOf<EventItem>()
    var displayList = listOf<EventItem>()   // display alert list

    inner class EventListAdapter() : RecyclerView.Adapter<EventListAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_event, parent, false)

            view.setOnClickListener {
                val eventPos = it.tag
                if (eventPos is Int && eventPos >= 0 && eventPos < displayList.size) {
                    val eventXml = mapOf(
                        "event" to displayList[eventPos].event
                    ).toXml()
                    val intent = Intent(it.context, GerryLiveActivity::class.java)
                    intent.putExtra("event", eventXml)
                    intent.putExtra("mdu", displayList[eventPos].mdu)
                    // startActivity(intent)
                }
            }

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
            if (item.eventFile.isBlank()) {
                holder.itemView.tag = -1
            } else {
                holder.itemView.tag = position
            }
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

    private val displayAdapter = EventListAdapter()

    private var updateRun = false

    private fun cbUpdateList(mdu: String, list: List<Any?>) {

        if (list.isNotEmpty()) {
            for (e in list) {
                eventList.add(EventItem(mdu, e))
            }
            eventListChanged = true
        }

    }

    private fun cbCompleteList() {
        if (updateRun) {
            // do filter and sort here
            if (eventListChanged) {
                // sort
                eventList.sortDescending()

                // filtering goes here

                displayList = eventList.toList()
                displayAdapter.notifyDataSetChanged()
                eventListChanged = false
            }

            // repeat
            mainHandler.postDelayed({
                updateList()
            }, 60000)
        }

    }

    private fun updateList() {
        var keys = synchronized(GerryService.gerryMDUs) {
            GerryService.gerryMDUs.keys
        }
        if (keys.isNotEmpty()) {
            var getListCommand = GerryMsg.CLIENT_GET_EVENTS
            if (type == ALERT_LIST) {
                getListCommand = GerryMsg.CLIENT_GET_ALERTS
            }

            val days = 30       // how many days to get event?
            val now = System.currentTimeMillis() / 1000
            var start = if (eventList.isNotEmpty()) {
                eventList[0].ts + 1
            } else {
                now - days * 24 * 3600
            }
            val obj = mapOf(
                "mduSet" to keys,
                "start" to start,
                "end" to (now + 24 * 3600),
                "command" to getListCommand,
                "cbUpdateList" to ::cbUpdateList,
                "cbCompleteList" to ::cbCompleteList
            )
            GerryService.instance
                ?.gerryHandler
                ?.obtainMessage(GerryService.MSG_GERRY_GET_EVENTS, obj)
                ?.sendToTarget()
        }
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

    override fun onResume() {
        super.onResume()
        updateRun = true
        updateList()
    }

    override fun onPause() {
        super.onPause()
        updateRun = false
    }
}