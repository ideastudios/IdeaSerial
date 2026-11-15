package com.github.ideastudios.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.github.ideastudios.serial.SerialSession;

/**
 * 串口页适配器：页数与会话层端口数一致
 */
public class PortPagerAdapter extends FragmentStateAdapter {

    public static final int PORT_COUNT = SerialSession.PORT_COUNT;

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
