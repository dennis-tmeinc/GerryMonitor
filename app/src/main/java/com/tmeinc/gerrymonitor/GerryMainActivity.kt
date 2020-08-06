package com.tmeinc.gerrymonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.tmeinc.gerrymonitor.ui.main.PlaceholderFragment

class GerryMainActivity : AppCompatActivity() {

    class PagerAdapter(private val context: Context, fm: FragmentManager) :
        FragmentPagerAdapter(fm) {

        // fragment cache
        private val fragmentList = mutableMapOf<Int, Fragment>()

        override fun getItem(position: Int): Fragment {

            // getItem is called to instantiate the fragment for the given page.
            val f = fragmentList[position]
            if (f != null) {
                return f
            }
            val nf = when (position) {
                0 -> {
                    StatusFragment()
                }
                1 -> {
                    EventFragment(EventFragment.ALERT_LIST)
                }
                2 -> {
                    EventFragment(EventFragment.EVENT_LIST)
                }
                else -> {
                    PlaceholderFragment().apply {
                        arguments = Bundle().apply {
                            putInt("section_number", position)
                        }
                    }
                }
            }
            fragmentList[position] = nf
            return nf
        }

        private val tabTitles = arrayOf(
            R.string.tab_title_1,
            R.string.tab_title_2,
            R.string.tab_title_3,
            R.string.tab_title_4
        )

        override fun getPageTitle(position: Int): CharSequence? {
            return context.resources.getString(tabTitles[position])
        }

        override fun getCount(): Int {
            return 3
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val pagerAdapter = PagerAdapter(this, supportFragmentManager)
        val viewPager: ViewPager = findViewById(R.id.view_pager)
        viewPager.adapter = pagerAdapter
        val tabs: TabLayout = findViewById(R.id.tabs)
        tabs.setupWithViewPager(viewPager)

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                val fragment = pagerAdapter.getItem(position)
                if (fragment is EventFragment) {
                    // fragment.updateList()
                }
            }
        })

        val fab: FloatingActionButton = findViewById(R.id.fab)
        fab.setOnClickListener {
            Snackbar.make(it, "Not yet!", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .show()
            /*
            val intent = Intent(it.context, GerrySettingsActivity::class.java)
                .putExtra("mdu", "")
            startActivity(intent)

             */
        }

        var page: Int
        getSharedPreferences("Settings", Service.MODE_PRIVATE)
            .apply {
                page = getInt("start_page", 0)
                if (page == 0) {        // use last closed page
                    page = getInt("last_page", 0)
                }
            }
        tabs.selectTab(tabs.getTabAt(page))

    }

    override fun onStart() {
        super.onStart()

        /* testing
            connect to self-signed https
        var x=""
        executorService.submit {
            val itWorks = getHttpContentUnSafe("https://192.168.119.60/")
            x = itWorks.substring(0)
        }
        */
    }

    override fun onStop() {
        super.onStop()
        val tabs: TabLayout = findViewById(R.id.tabs)
        tabs.selectedTabPosition
        getSharedPreferences("Settings", Service.MODE_PRIVATE)
            .edit()
            .apply {
                this.putInt("last_page", tabs.selectedTabPosition)
            }
            .apply()
    }
}