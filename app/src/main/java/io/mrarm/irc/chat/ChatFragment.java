package io.mrarm.irc.chat;

import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.MarginLayoutParamsCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.List;
import java.util.UUID;

import io.mrarm.chatlib.dto.NickWithPrefix;
import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.chatlib.irc.ServerConnectionApi;
import io.mrarm.irc.ChannelNotificationManager;
import io.mrarm.irc.MainActivity;
import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.R;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.ServerConnectionManager;
import io.mrarm.irc.config.CommandAliasManager;
import io.mrarm.irc.util.ColoredTextBuilder;
import io.mrarm.irc.util.IRCColorUtils;
import io.mrarm.irc.util.ImageViewTintUtils;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.util.SimpleTextVariableList;
import io.mrarm.irc.util.SimpleTextWatcher;
import io.mrarm.irc.view.ChatAutoCompleteEditText;
import io.mrarm.irc.view.TextFormatBar;

public class ChatFragment extends Fragment implements
        ServerConnectionInfo.ChannelListChangeListener,
        NotificationManager.UnreadMessageCountCallback,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String ARG_SERVER_UUID = "server_uuid";
    private static final String ARG_CHANNEL_NAME = "channel";

    private ServerConnectionInfo mConnectionInfo;

    private AppBarLayout mAppBar;
    private TabLayout mTabLayout;
    private ChatPagerAdapter mSectionsPagerAdapter;
    private ViewPager mViewPager;
    private DrawerLayout mDrawerLayout;
    private ChannelMembersAdapter mChannelMembersAdapter;
    private ChatSuggestionsAdapter mChannelMembersListAdapter;
    private ChatAutoCompleteEditText mSendText;
    private View mFormatBarDivider;
    private TextFormatBar mFormatBar;
    private ImageView mSendIcon;
    private ImageView mTabIcon;
    private View mCommandErrorContainer;
    private TextView mCommandErrorText;
    private int mNormalToolbarInset;

    public static ChatFragment newInstance(ServerConnectionInfo server, String channel) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SERVER_UUID, server.getUUID().toString());
        if (channel != null)
            args.putString(ARG_CHANNEL_NAME, channel);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.chat_fragment, container, false);

        UUID connectionUUID = UUID.fromString(getArguments().getString(ARG_SERVER_UUID));
        mConnectionInfo = ServerConnectionManager.getInstance(getContext()).getConnection(connectionUUID);
        String requestedChannel = getArguments().getString(ARG_CHANNEL_NAME);

        mAppBar = rootView.findViewById(R.id.appbar);

        Toolbar toolbar = rootView.findViewById(R.id.toolbar);
        mNormalToolbarInset = toolbar.getContentInsetStartWithNavigation();

        ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(mConnectionInfo.getName());

        ((MainActivity) getActivity()).addActionBarDrawerToggle(toolbar);

        mSectionsPagerAdapter = new ChatPagerAdapter(getContext(), getChildFragmentManager(), mConnectionInfo);

        mViewPager = rootView.findViewById(R.id.container);
        mViewPager.setAdapter(mSectionsPagerAdapter);

        if (requestedChannel != null)
            setCurrentChannel(requestedChannel);

        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {
            }

            @Override
            public void onPageSelected(int i) {
                ((MainActivity) getActivity()).getDrawerHelper().setSelectedChannel(mConnectionInfo,
                        mSectionsPagerAdapter.getChannel(i));
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });

        mConnectionInfo.addOnChannelListChangeListener(this);

        mTabLayout = rootView.findViewById(R.id.tabs);
        mTabLayout.setupWithViewPager(mViewPager, false);

        mSectionsPagerAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                updateTabLayoutTabs();
            }
        });
        mConnectionInfo.getNotificationManager().addUnreadMessageCountCallback(this);
        updateTabLayoutTabs();

        mDrawerLayout = rootView.findViewById(R.id.drawer_layout);
        mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        mChannelMembersAdapter = new ChannelMembersAdapter(mConnectionInfo, null);
        RecyclerView membersRecyclerView = rootView.findViewById(R.id.members_list);
        membersRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        membersRecyclerView.setAdapter(mChannelMembersAdapter);

        mFormatBar = rootView.findViewById(R.id.format_bar);
        mFormatBarDivider = rootView.findViewById(R.id.format_bar_divider);
        mSendText = rootView.findViewById(R.id.send_text);
        mSendIcon = rootView.findViewById(R.id.send_button);
        mTabIcon = rootView.findViewById(R.id.tab_button);

        mSendText.setFormatBar(mFormatBar);
        mSendText.setCustomSelectionActionModeCallback(new FormatItemActionMode());

        mFormatBar.setExtraButton(R.drawable.ic_close, getString(R.string.action_close), (View v) -> {
            setFormatBarVisible(false);
        });

        RecyclerView suggestionsRecyclerView = rootView.findViewById(R.id.suggestions_list);
        suggestionsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mChannelMembersListAdapter = new ChatSuggestionsAdapter(mConnectionInfo, null);
        mSendText.setSuggestionsListView(rootView.findViewById(R.id.suggestions_container), rootView.findViewById(R.id.suggestions_card), suggestionsRecyclerView);
        mSendText.setAdapter(mChannelMembersListAdapter);
        mSendText.setCommandListAdapter(new CommandListSuggestionsAdapter(getContext()));
        mSendText.setConnectionContext(mConnectionInfo);
        if (mConnectionInfo.getApiInstance() instanceof ServerConnectionApi)
            mSendText.setChannelTypes(((ServerConnectionApi) mConnectionInfo.getApiInstance())
                    .getServerConnectionData().getSupportList().getSupportedChannelTypes());
        rootView.findViewById(R.id.suggestions_dismiss).setOnTouchListener((View view, MotionEvent motionEvent) -> {
            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN)
                mSendText.dismissDropDown();
            return true;
        });

        ImageViewTintUtils.setTint(mSendIcon, 0x54000000);

        mSendText.addTextChangedListener(new SimpleTextWatcher((Editable s) -> {
            int accentColor = getResources().getColor(R.color.colorAccent);
            if (s.length() > 0)
                ImageViewTintUtils.setTint(mSendIcon, accentColor);
            else
                ImageViewTintUtils.setTint(mSendIcon, 0x54000000);
            mCommandErrorContainer.setVisibility(View.GONE); // hide the error
        }));
        mSendText.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND)
                sendMessage();
            return false;
        });
        mSendIcon.setOnClickListener((View view) -> {
            sendMessage();
        });

        mTabIcon.setOnClickListener((View v) -> {
            mSendText.requestTabComplete();
        });

        mCommandErrorContainer = rootView.findViewById(R.id.command_error_card);
        mCommandErrorText = rootView.findViewById(R.id.command_error_text);
        mCommandErrorText.setMovementMethod(new LinkMovementMethod());
        rootView.findViewById(R.id.command_error_close).setOnClickListener((View v) -> mCommandErrorContainer.setVisibility(View.GONE));

        rootView.addOnLayoutChangeListener((View v, int left, int top, int right, int bottom,
                                            int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
            int height = bottom - top;
            mAppBar.post(() -> {
                if (!isAdded())
                    return;
                if (height < getResources().getDimensionPixelSize(R.dimen.collapse_toolbar_activate_height)) {
                    mAppBar.setVisibility(View.GONE);
                } else {
                    updateToolbarCompactLayoutStatus(height);
                    mAppBar.setVisibility(View.VISIBLE);
                }
            });
        });
        mTabLayout.addOnLayoutChangeListener((View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
            if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom)
                return;
            mTabLayout.setScrollPosition(mTabLayout.getSelectedTabPosition(), 0.f, false);
        });

        SettingsHelper s = SettingsHelper.getInstance(getContext());
        s.addPreferenceChangeListener(SettingsHelper.PREF_CHAT_APPBAR_COMPACT_MODE, this);
        s.addPreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_SHOW_BUTTON, this);
        s.addPreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_DOUBLE_TAP, this);

        setTabButtonVisible(s.isNickAutocompleteButtonVisible());
        setDoubleTapCompleteEnabled(s.isNickAutocompleteDoubleTapEnabled());

        return rootView;
    }

    private void updateTabLayoutTabs() {
        mTabLayout.removeAllTabs();
        final int c = mSectionsPagerAdapter.getCount();
        for (int i = 0; i < c; i++) {
            TabLayout.Tab tab = mTabLayout.newTab();
            tab.setText(mSectionsPagerAdapter.getPageTitle(i));
            tab.setTag(mSectionsPagerAdapter.getChannel(i));
            tab.setCustomView(R.layout.chat_tab);
            TextView textView = tab.getCustomView().findViewById(android.R.id.text1);
            textView.setTextColor(mTabLayout.getTabTextColors());
            updateTabLayoutTab(tab);
            mTabLayout.addTab(tab, false);
        }

        final int currentItem = mViewPager.getCurrentItem();
        if (currentItem != mTabLayout.getSelectedTabPosition() && currentItem < mTabLayout.getTabCount())
            mTabLayout.getTabAt(currentItem).select();
    }

    private void updateTabLayoutTab(TabLayout.Tab tab) {
        String channel = (String) tab.getTag();
        boolean highlight = false;
        if (channel != null) {
            ChannelNotificationManager data = mConnectionInfo.getNotificationManager().getChannelManager(channel, false);
            if (data != null)
                highlight = data.hasUnreadMessages();
        }
        tab.getCustomView().findViewById(R.id.notification_icon).setVisibility(highlight ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (getView() != null) {
            updateToolbarCompactLayoutStatus(getView().getBottom() - getView().getTop());
            SettingsHelper s = SettingsHelper.getInstance(getContext());
            setTabButtonVisible(s.isNickAutocompleteButtonVisible());
            setDoubleTapCompleteEnabled(s.isNickAutocompleteDoubleTapEnabled());
        }
    }

    public void updateToolbarCompactLayoutStatus(int height) {
        String mode = SettingsHelper.getInstance(getContext()).getChatAppbarCompactMode();
        boolean enabled = mode.equals(SettingsHelper.COMPACT_MODE_ALWAYS) ||
                (mode.equals(SettingsHelper.COMPACT_MODE_AUTO) &&
                        height < getResources().getDimensionPixelSize(R.dimen.compact_toolbar_activate_height));
        setUseToolbarCompactLayout(enabled);
    }

    public void setUseToolbarCompactLayout(boolean enable) {
        Toolbar toolbar = ((MainActivity) getActivity()).getToolbar();
        if (enable == (mTabLayout.getParent() == toolbar))
            return;
        ((ViewGroup) mTabLayout.getParent()).removeView(mTabLayout);
        if (enable) {
            ViewGroup.LayoutParams params = new Toolbar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mTabLayout.setLayoutParams(params);
            toolbar.addView(mTabLayout);
            toolbar.setContentInsetStartWithNavigation(0);
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mTabLayout.setLayoutParams(params);
        } else {
            mAppBar.addView(mTabLayout);
            toolbar.setContentInsetStartWithNavigation(mNormalToolbarInset);
            ViewGroup.LayoutParams params = mTabLayout.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mTabLayout.setLayoutParams(params);
        }
    }

    public void setTabsHidden(boolean hidden) {
        mTabLayout.setVisibility(hidden ? View.GONE : View.VISIBLE);
    }

    public void setFormatBarVisible(boolean visible) {
        if (visible) {
            mFormatBar.setVisibility(View.VISIBLE);
            mFormatBarDivider.setVisibility(View.VISIBLE);
        } else {
            mFormatBar.setVisibility(View.GONE);
            mFormatBarDivider.setVisibility(View.GONE);
        }
    }

    public void setTabButtonVisible(boolean visible) {
        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams)
                mSendText.getLayoutParams();
        if (visible) {
            MarginLayoutParamsCompat.setMarginStart(layoutParams, 0);
            mTabIcon.setVisibility(View.VISIBLE);
        } else {
            MarginLayoutParamsCompat.setMarginStart(layoutParams,
                    getResources().getDimensionPixelSize(R.dimen.message_edit_text_margin_left));
            mTabIcon.setVisibility(View.GONE);
        }
        mSendText.setLayoutParams(layoutParams);
    }

    public void setDoubleTapCompleteEnabled(boolean enabled) {
        if (enabled) {
            GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    mSendText.requestTabComplete();
                    return true;
                }
            });
            mSendText.setOnTouchListener((View v, MotionEvent event) -> detector.onTouchEvent(event));
        } else {
            mSendText.setOnTouchListener(null);
        }
    }

    public void sendMessage() {
        String text = IRCColorUtils.convertSpannableToIRCString(getContext(), mSendText.getText());
        if (text.length() == 0)
            return;
        String channel = mSectionsPagerAdapter.getChannel(mViewPager.getCurrentItem());
        if (text.charAt(0) == '/') {
            SimpleTextVariableList vars = new SimpleTextVariableList();
            vars.set(CommandAliasManager.VAR_CHANNEL, channel);
            vars.set(CommandAliasManager.VAR_MYNICK, mConnectionInfo.getUserNick());
            try {
                if (CommandAliasManager.getInstance(getContext()).processCommand((IRCConnection) mConnectionInfo.getApiInstance(), text.substring(1), vars)) {
                    mSendText.setText("");
                    return;
                }
            } catch (RuntimeException e) {
                mCommandErrorText.setText(R.string.command_error_internal);
                mCommandErrorContainer.setVisibility(View.VISIBLE);
                mSendText.dismissDropDown();
                return;
            }
            ColoredTextBuilder builder = new ColoredTextBuilder();
            builder.append(getString(R.string.command_error_not_found));
            builder.append("  ");
            builder.append(getString(R.string.command_send_raw), new ClickableSpan() {
                @Override
                public void onClick(View view) {
                    ((IRCConnection) mConnectionInfo.getApiInstance()).sendCommandRaw(text.substring(1), null, null);
                    mCommandErrorContainer.setVisibility(View.GONE);
                }
            });
            mCommandErrorText.setText(builder.getSpannable());
            mCommandErrorContainer.setVisibility(View.VISIBLE);
            mSendText.dismissDropDown();
            return;
        }
        mSendText.setText("");
        mConnectionInfo.getApiInstance().sendMessage(channel, text, null, null);
    }

    public boolean hasSendMessageTextSelection() {
        return (mSendText != null && mSendText.getSelectionEnd() - mSendText.getSelectionStart() > 0);
    }

    @Override
    public void onDestroyView() {
        mConnectionInfo.removeOnChannelListChangeListener(this);
        mConnectionInfo.getNotificationManager().removeUnreadMessageCountCallback(this);
        SettingsHelper s = SettingsHelper.getInstance(getContext());
        s.removePreferenceChangeListener(SettingsHelper.PREF_CHAT_APPBAR_COMPACT_MODE, this);
        s.removePreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_SHOW_BUTTON, this);
        s.removePreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_DOUBLE_TAP, this);
        super.onDestroyView();
    }

    public ServerConnectionInfo getConnectionInfo() {
        return mConnectionInfo;
    }

    public void setCurrentChannel(String channel) {
        mViewPager.setCurrentItem(mSectionsPagerAdapter.findChannel(channel));
    }

    public void setCurrentChannelMembers(List<NickWithPrefix> members) {
        mChannelMembersAdapter.setMembers(members);
        mChannelMembersListAdapter.setMembers(members);
        if (members == null || members.size() == 0)
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        else
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
    }

    public String getCurrentChannel() {
        return mSectionsPagerAdapter.getChannel(mViewPager.getCurrentItem());
    }

    @Override
    public void onChannelListChanged(ServerConnectionInfo connection, List<String> newChannels) {
        getActivity().runOnUiThread(() -> {
            mSectionsPagerAdapter.updateChannelList();
        });
    }

    @Override
    public void onUnreadMessageCountChanged(ServerConnectionInfo info, String channel,
                                            int messageCount, int oldMessageCount) {
        if (messageCount == 0 || (messageCount > 0 && oldMessageCount == 0)) {
            getActivity().runOnUiThread(() -> {
                int tabNumber = mSectionsPagerAdapter.findChannel(channel);
                updateTabLayoutTab(mTabLayout.getTabAt(tabNumber));
            });
        }
    }

    public void closeDrawer() {
        mDrawerLayout.closeDrawer(GravityCompat.END, false);
    }

    private class FormatItemActionMode implements ActionMode.Callback {

        private MenuItem mFormatMenuItem;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mFormatMenuItem = menu.add(R.string.message_format)
                    .setIcon(R.drawable.ic_text_format);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (mFormatMenuItem == item) {
                setFormatBarVisible(true);
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
        }

    }

}
