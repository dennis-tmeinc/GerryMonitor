package com.tmeinc.gerrymonitor

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class PlayEventActivity : AppCompatActivity() {

    lateinit var mdu: String
    private lateinit var viewPager: ViewPager2
    lateinit var tabs: TabLayout

    data class CamInfo(val id: Int, val name: String, val bgImg: String, val roomName: String)

    var cameraList = mutableListOf<CamInfo>()

    inner class PagerAdapter :
        FragmentStateAdapter(this) {

        override fun createFragment(position: Int): Fragment {
            return if (position < cameraList.size) {
                LiveFragment(mdu, position)
            } else {
                LiveFragment(mdu, 0)
            }
        }

        override fun getItemCount(): Int {
            return cameraList.size
        }
    }

    // cam info call back
    private fun cbCamInfo(camList: List<Any?>) {
        cameraList.clear()
        for (i in camList.indices) {
            val cam = camList[i]
            val bg = cam.getLeafString("bg")
            if (!bg.isBlank() && cam.getLeafInt("enable") != 0)
                cameraList.add(
                    CamInfo(
                        i,
                        cam.getLeafString("name"),
                        bg,
                        cam.getLeafString("room")
                    )
                )
        }
        if (cameraList.isEmpty()) {
            Toast.makeText(this, "Device information not available!", Toast.LENGTH_LONG).show()
            finish()
        } else {
            TabLayoutMediator(tabs, viewPager) { tab, position ->
                tab.text = cameraList[position].name
            }.attach()
            (viewPager.adapter as FragmentStateAdapter).notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play_event)

        val iMdu = intent.getStringExtra("mdu")
        if (iMdu != null) {
            mdu = iMdu
        } else {
            finish()        // don't know what to do
            return
        }

        viewPager = findViewById(R.id.view_pager)
        viewPager.adapter = PagerAdapter()

        tabs = findViewById(R.id.tabs)

        val fab: FloatingActionButton = findViewById(R.id.fab)

        fab.setOnClickListener {
            this@PlayEventActivity.onBackPressed()
        }

        // get camera info
        GerryService.instance
            ?.gerryHandler
            ?.obtainMessage(
                GerryService.MSG_GERRY_GET_CAM_INFO, mapOf(
                    "mdu" to mdu,
                    "cbCamInfo" to ::cbCamInfo
                )
            )
            ?.sendToTarget()

        // original codes
        /*
        val sectionsPagerAdapter = SectionsPagerAdapter(this, supportFragmentManager)
        val viewPager: ViewPager = findViewById(R.id.view_pager)
        viewPager.adapter = sectionsPagerAdapter
        val tabs: TabLayout = findViewById(R.id.tabs)
        tabs.setupWithViewPager(viewPager)
        */
    }
}