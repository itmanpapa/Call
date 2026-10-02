package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * A minimal bar chart (no chart library): one bar per value, the last bar (today)
 * highlighted, the maximum written at the top and a base line at the bottom.
 */
public class StatsBarChartView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lastBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private int[] values = new int[0];

    public StatsBarChartView(Context context) {
        this(context, null);
    }

    public StatsBarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        barPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimary, 0xFF3F51B5));
        lastBarPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorTertiary, 0xFF7D5260));
        emptyPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0));
        linePaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOutline, 0xFF79747E));
        linePaint.setStrokeWidth(Math.max(1f, density));
        textPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F));
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                getResources().getDisplayMetrics()));
    }

    /** @param values the bars, oldest first */
    public void setValues(int[] values) {
        this.values = values != null ? values.clone() : new int[0];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int n = values.length;
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();
        float bottom = getHeight() - getPaddingBottom();
        if (n == 0 || right <= left || bottom <= top) return;

        int max = 0;
        for (int v : values) max = Math.max(max, v);

        // the maximum above the bars
        String maxLabel = String.valueOf(max);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float textX = rtl ? right - textPaint.measureText(maxLabel) : left;
        canvas.drawText(maxLabel, textX, top - fm.ascent, textPaint);

        float chartTop = top + textHeight + 4 * density;
        float chartBottom = bottom - linePaint.getStrokeWidth();
        float chartHeight = chartBottom - chartTop;
        if (chartHeight <= 0) return;

        float slot = (right - left) / n;
        float gap = Math.min(slot * 0.25f, 4 * density);
        float radius = Math.min((slot - gap) / 2, 3 * density);
        float minHeight = 2 * density;

        for (int i = 0; i < n; i++) {
            // the newest day is on the right (on the left for right-to-left languages)
            int slotIndex = rtl ? n - 1 - i : i;
            float x0 = left + slotIndex * slot + gap / 2;
            float x1 = x0 + slot - gap;
            int v = values[i];
            if (v <= 0 || max == 0) {
                rect.set(x0, chartBottom - minHeight, x1, chartBottom);
                canvas.drawRect(rect, emptyPaint);
                continue;
            }
            float h = Math.max(minHeight, chartHeight * v / max);
            rect.set(x0, chartBottom - h, x1, chartBottom);
            canvas.drawRoundRect(rect, radius, radius, i == n - 1 ? lastBarPaint : barPaint);
        }

        canvas.drawLine(left, bottom - linePaint.getStrokeWidth() / 2, right,
                bottom - linePaint.getStrokeWidth() / 2, linePaint);
    }

}
