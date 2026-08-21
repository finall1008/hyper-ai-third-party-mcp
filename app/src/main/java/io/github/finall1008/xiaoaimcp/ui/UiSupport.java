package io.github.finall1008.xiaoaimcp.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiSupport {
    private UiSupport() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, float sizeSp) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(30, 34, 45));
        return view;
    }

    static TextView title(Context context, String value) {
        TextView view = text(context, value, 22);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(context, 4), 0, dp(context, 8));
        return view;
    }

    static EditText labeledEdit(LinearLayout parent, String label, String hint) {
        Context context = parent.getContext();
        TextView labelView = text(context, label, 13);
        labelView.setTextColor(Color.rgb(90, 95, 108));
        labelView.setPadding(0, dp(context, 12), 0, 0);
        parent.addView(labelView, matchWrap());

        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setTextSize(16);
        editText.setSingleLine(true);
        parent.addView(editText, matchWrap());
        return editText;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    static void setEnabledRecursively(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursively(group.getChildAt(i), enabled);
            }
        }
    }
}
