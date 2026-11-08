package com.github.ideastudios.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * 4 个串口页（COM A/B/C/D）
 */
public class PortPagerAdapter extends FragmentStateAdapter {

    public static final int PORT_COUNT = 4;

    public PortPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return PortFragment.newInstance(position);
    }

    @Override
    public int getItemCount() {
        return PORT_COUNT;
    }
}
