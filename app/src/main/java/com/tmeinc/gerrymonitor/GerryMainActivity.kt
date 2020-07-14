package com.tmeinc.gerrymonitor

import android.app.Service
import android.os.Bundle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import com.tmeinc.gerrymonitor.SectionsPagerAdapter

class GerryMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val sectionsPagerAdapter = SectionsPagerAdapter(this, supportFragmentManager)
        val viewPager: ViewPager = findViewById(R.id.view_pager)
        viewPager.adapter = sectionsPagerAdapter
        val tabs: TabLayout = findViewById(R.id.tabs)
        tabs.setupWithViewPager(viewPager)

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener{
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            }

            override fun onPageSelected(position: Int) {
                val fragment = sectionsPagerAdapter.getItem(position)
                if( fragment is EventFragment ) {
                    // fragment.updateList()
                }
            }
        })

        val fab: FloatingActionButton = findViewById(R.id.fab)
        fab.setOnClickListener { view ->
            Snackbar.make(view, "Not yet!", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }

        var page: Int
        getSharedPreferences("Settings", Service.MODE_PRIVATE).apply {
            page = this.getInt("start_page", 0)
            if( page==0 )   {        // use last closed page
                page = this.getInt("last_page", 0)
            }
        }
        tabs.selectTab(tabs.getTabAt(page))

    }

    override fun onStop() {
        super.onStop()
        val tabs: TabLayout = findViewById(R.id.tabs)
        tabs.selectedTabPosition
        getSharedPreferences("Settings", Service.MODE_PRIVATE).edit().apply {
            this.putInt("last_page", tabs.selectedTabPosition)
        }.apply()
    }
}