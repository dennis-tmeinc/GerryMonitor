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
    var event: Any? = null
    private lateinit var viewPager: ViewPager2
    lateinit var tabs: TabLayout

    var cameraList =listOf<Any?>()

    inner class PagerAdapter :
        FragmentStateAdapter(this) {

        override fun createFragment(position: Int): Fragment {
            return if (position < cameraList.size) {
                PlayFragment(mdu, position)
            } else {
                PlayFragment(mdu, 0)
            }
        }

        override fun getItemCount(): Int {
            return cameraList.size
        }
    }

    private fun setupEvents() {
        // read event file
        executorService
            .submit {
                // load background files
                val genFile = event.getLeafString("event/path")
                if (genFile.isBlank()) {
                    return@submit
                }
                val genXML = gerryReadFile(genFile)
                val gen = String(genXML).xmlObj()
                val camList = gen.getLeafArray("mdup/animation/camera")
                if (camList.isNotEmpty()) {
                    cameraList = camList
                    mainHandler.post{
                        TabLayoutMediator(tabs, viewPager) { tab, position ->
                            val camNum = cameraList.getLeafInt("${position}/num")
                            tab.text = "Sensor #${camNum}"
                        }.attach()
                        (viewPager.adapter as FragmentStateAdapter).notifyDataSetChanged()
                    }
                }
                else {
                    mainHandler.post{
                        finish()
                        Toast.makeText(
                            this@PlayEventActivity,
                            "Invalid alert playback file!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play_event)

        val iMdu = intent.getStringExtra("mdu")
        event = intent.getStringExtra("event")?.xmlObj()
        if (iMdu != null && event is Map<*, *>) {
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
            onBackPressed()
        }

        setupEvents()


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