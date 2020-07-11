package com.tmeinc.gerrymonitor

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.tmeinc.gerrymonitor.ui.main.PlaceholderFragment

class SectionsPagerAdapter(private val context: Context, fm: FragmentManager) : FragmentPagerAdapter(fm) {

    private val fragmentList = mutableMapOf<Int,Fragment>()

    override fun getItem(position: Int): Fragment {

        // getItem is called to instantiate the fragment for the given page.
        val f = fragmentList[position]
        if( f!=null ) {
            return f
        }
        val nf = when (position) {
            0 -> {
                StatusFragment()
            }
            1 -> {
                EventFragment( EventFragment.ALERT_LIST )
            }
            2 -> {
                EventFragment( EventFragment.EVENT_LIST )
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

    private val TAB_TITLES = arrayOf(
        R.string.tab_title_1,
        R.string.tab_title_2,
        R.string.tab_title_3,
        R.string.tab_title_4
    )

    override fun getPageTitle(position: Int): CharSequence? {
        return context.resources.getString(TAB_TITLES[position])
    }

    override fun getCount(): Int {
        // Show 3 pages.
        return 3
    }
}