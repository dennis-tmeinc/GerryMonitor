package com.tmeinc.gerrymonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONArray

class GerryLiveActivity : AppCompatActivity() {

    lateinit var mdu: String
    private lateinit var viewPager: ViewPager2

    data class CamInfo(val id: Int, val bgImg: String)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gerry_live)

        val iMdu = intent.getStringExtra("mdu")
        if (iMdu != null) {
            mdu = iMdu
        } else {
            finish()        // don't know what to do
            return
        }

        viewPager = findViewById(R.id.view_pager)
        viewPager.adapter = PagerAdapter()

        val tabs: TabLayout = findViewById(R.id.tabs)
        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = "Camera ${cameraList[position].id + 1}"
        }.attach()

        val fab: FloatingActionButton = findViewById(R.id.fab)

        fab.setOnClickListener {
            this@GerryLiveActivity.onBackPressed()
        }

        executorService.submit {
            val dbRes = gerryDB("SELECT * FROM camera WHERE mdu_id = '${mdu}'")
            if (dbRes["res"] == 1) {
                val o = dbRes["output"]
                if (o is JSONArray) {
                    for (i in 0 until o.length()) {
                        val id = o.getLeafInt("$i/id")
                        val bg = o.getLeafString("$i/roc_background_img_path")
                        if (bg.length > 2)
                            cameraList.add(CamInfo(id, bg))
                        // (viewPager.adapter as FragmentStateAdapter).notifyItemInserted(cameraList.lastIndex)
                    }
                    mainHandler.post {
                        (viewPager.adapter as FragmentStateAdapter).notifyDataSetChanged()
                    }
                }
            }
        }

    }
}