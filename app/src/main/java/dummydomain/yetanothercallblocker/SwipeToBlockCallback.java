package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.Objects;

/**
 * Swipe a call log entry to the left to add the number to the blacklist.
 */
public class SwipeToBlockCallback extends ItemTouchHelper.SimpleCallback {

    public interface Listener {
        /** @return whether the item at the position can be blocked */
        boolean canBlock(int position);

        void onBlock(int position);
    }

    private final Listener listener;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final int iconMargin;

    public SwipeToBlockCallback(Context context, Listener listener) {
        super(0, ItemTouchHelper.LEFT);
        this.listener = listener;

        backgroundPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6));

        icon = DrawableCompat.wrap(Objects.requireNonNull(
                ContextCompat.getDrawable(context, R.drawable.ic_block_24dp)).mutate());
        DrawableCompat.setTint(icon, MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410002));

        iconMargin = context.getResources().getDimensionPixelSize(R.dimen.item_padding);
    }

    @Override
    public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder) {
        int position = viewHolder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || !listener.canBlock(position)) return 0;
        return super.getSwipeDirs(recyclerView, viewHolder);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder,
                          @NonNull RecyclerView.ViewHolder target) {
        return false;
    }

    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
        return 0.35f;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        int position = viewHolder.getBindingAdapterPosition();
        RecyclerView.Adapter<?> adapter = viewHolder.getBindingAdapter();
        if (adapter != null && position != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(position); // return the item to its place
            listener.onBlock(position);
        }
    }

    @Override
    public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder,
                            float dX, float dY, int actionState, boolean isCurrentlyActive) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
            View itemView = viewHolder.itemView;

            c.drawRect(itemView.getRight() + dX, itemView.getTop(),
                    itemView.getRight(), itemView.getBottom(), backgroundPaint);

            int iconSize = icon.getIntrinsicHeight();
            int top = itemView.getTop() + (itemView.getHeight() - iconSize) / 2;
            int right = itemView.getRight() - iconMargin;
            icon.setBounds(right - icon.getIntrinsicWidth(), top, right, top + iconSize);
            if (-dX > iconMargin + icon.getIntrinsicWidth()) {
                icon.draw(c);
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
    }

}
