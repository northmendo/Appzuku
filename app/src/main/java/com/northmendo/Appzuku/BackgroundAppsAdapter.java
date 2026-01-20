package com.northmendo.Appzuku;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import androidx.core.content.ContextCompat;

import com.northmendo.Appzuku.databinding.ItemBinding;

import java.util.List;

public class BackgroundAppsAdapter extends BaseAdapter {

    private final Context context;
    private final List<AppModel> data;
    private final LayoutInflater inflater;
    private final int density;
    private final int selectedColor;
    private final int defaultColor;
    private OnAppActionListener actionListener;

    // Listener interface for app actions
    public interface OnAppActionListener {
        void onKillApp(String packageName);
        void onToggleWhitelist(String packageName, int position);
    }

    public BackgroundAppsAdapter(Context context, List<AppModel> data) {
        this.context = context;
        this.data = data;
        this.inflater = LayoutInflater.from(context);
        this.density = (int) context.getResources().getDisplayMetrics().density;

        // Retrieve colors from resources
        this.selectedColor = ContextCompat.getColor(context, R.color.list_item_selected_color);
        this.defaultColor = ContextCompat.getColor(context, R.color.list_item_default_color);
    }

    public void setOnAppActionListener(OnAppActionListener listener) {
        this.actionListener = listener;
    }

    // Creates a circular button background with ripple effect
    private Drawable createButtonBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(context, R.color.button_background_color));
        background.setCornerRadius(density * 360);

        return new RippleDrawable(
                new ColorStateList(new int[][]{{}}, new int[]{ContextCompat.getColor(context, R.color.button_ripple_color)}),
                background,
                null
        );
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public AppModel getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    // Creates and binds the view for each list item
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ItemBinding itemBinding;

        if (convertView == null) {
            itemBinding = ItemBinding.inflate(inflater, parent, false);
            convertView = itemBinding.getRoot();
            convertView.setTag(itemBinding);
        } else {
            itemBinding = (ItemBinding) convertView.getTag();
        }

        AppModel app = getItem(position);

        itemBinding.appName.setText(app.getAppName());
        itemBinding.appPkg.setText(app.getPackageName());
        itemBinding.appRam.setText(app.getAppRam());
        itemBinding.appIcon.setImageDrawable(app.getAppIcon());

        // Show whitelist indicator
        itemBinding.whitelistIcon.setVisibility(app.isWhitelisted() ? View.VISIBLE : View.GONE);

        // Configure the kill button appearance
        itemBinding.imageview2.setBackground(createButtonBackground());
        itemBinding.imageview2.setElevation(density * 2);
        itemBinding.imageview2.setClickable(true);
        itemBinding.imageview2.setFocusable(true);

        final String pkg = app.getPackageName();
        final int pos = position;

        // Set kill button click listener
        Object tag = itemBinding.imageview2.getTag();
        if (!(tag instanceof String) || !tag.equals(pkg)) {
            itemBinding.imageview2.setOnClickListener(v -> {
                if (actionListener != null) {
                    AppModel currentApp = getItem(pos);
                    if (currentApp != null) {
                        actionListener.onKillApp(currentApp.getPackageName());
                    }
                }
            });
            itemBinding.imageview2.setTag(pkg);
        }

        // Set long-press listener for whitelist toggle
        itemBinding.linear1.setOnLongClickListener(v -> {
            if (actionListener != null && !app.isProtected()) {
                actionListener.onToggleWhitelist(pkg, pos);
                return true;
            }
            return false;
        });

        // Update background and button visibility based on selection and whitelist
        itemBinding.linear1.setSelected(app.isSelected());
        itemBinding.imageview2.setVisibility(app.isSelected() ? View.GONE : View.VISIBLE);

        if (app.isProtected()) {
            itemBinding.getRoot().setAlpha(0.4f);
            itemBinding.imageview2.setVisibility(View.GONE);
            itemBinding.linear1.setSelected(false);
        } else if (app.isWhitelisted()) {
            // Whitelisted apps: slightly different appearance, hide kill button
            itemBinding.getRoot().setAlpha(0.85f);
            itemBinding.imageview2.setVisibility(View.GONE);
            itemBinding.linear1.setSelected(false);
        } else {
            itemBinding.getRoot().setAlpha(1.0f);
            itemBinding.imageview2.setVisibility(app.isSelected() ? View.GONE : View.VISIBLE);
            itemBinding.linear1.setSelected(app.isSelected());
        }
        return convertView;
    }
}
