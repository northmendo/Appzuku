package com.yn.shappky.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import com.yn.shappky.R;
import com.yn.shappky.model.AppModel;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FilterAppsAdapter extends BaseAdapter {

    private final List<AppModel> apps;
    private final LayoutInflater inflater;

    public FilterAppsAdapter(Context context, List<AppModel> apps, Set<String> hiddenApps) {
        this.inflater = LayoutInflater.from(context);

        Collections.sort(apps, new Comparator<AppModel>() {
            @Override
            public int compare(AppModel app1, AppModel app2) {
                if (app1.isSystemApp() == app2.isSystemApp()) {
                    return app1.getAppName().compareToIgnoreCase(app2.getAppName());
                }
                return app1.isSystemApp() ? 1 : -1; 
            }
        });

        this.apps = apps;

        for (AppModel app : apps) {
            if (hiddenApps.contains(app.getPackageName())) {
                app.setSelected(true);
            }
        }
    }

    @Override
    public int getCount() {
        return apps.size();
    }

    @Override
    public AppModel getItem(int position) {
        return apps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_filter_app, parent, false);
            holder = new ViewHolder();
            holder.appName = convertView.findViewById(R.id.filter_app_name);
            holder.appIcon = convertView.findViewById(R.id.filter_app_icon);
            holder.checkBox = convertView.findViewById(R.id.filter_app_checkbox);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        AppModel app = getItem(position);
        holder.appName.setText(app.getAppName());
        holder.appIcon.setImageDrawable(app.getAppIcon());
        holder.checkBox.setChecked(app.isSelected());

        holder.checkBox.setOnClickListener(v -> {
            boolean isChecked = ((CheckBox) v).isChecked();
            app.setSelected(isChecked);
        });

        convertView.setOnClickListener(v -> {
            holder.checkBox.performClick(); 
        });

        return convertView;
    }

    public Set<String> getSelectedPackages() {
        Set<String> selected = new HashSet<>();
        for (AppModel app : apps) {
            if (app.isSelected()) {
                selected.add(app.getPackageName());
            }
        }
        return selected;
    }

    public static class ViewHolder {
        public TextView appName;
        public ImageView appIcon;
        public CheckBox checkBox;
    }
}