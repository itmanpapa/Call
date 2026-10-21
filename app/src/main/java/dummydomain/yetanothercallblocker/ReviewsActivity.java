package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.recyclerview.widget.RecyclerView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.sia.model.CommunityReview;

import static dummydomain.yetanothercallblocker.IntentHelper.clearTop;

/**
 * The online reviews of a number (the community database of the "Should I Answer" service).
 *
 * <p>Uses the standard screen frame of the app (the top app bar with the number as the title
 * and the list below it), so it looks like the other screens and scrolls properly.</p>
 */
public class ReviewsActivity extends BaseActivity {

    private static final String PARAM_NUMBER = "param_number";

    private static final Logger LOG = LoggerFactory.getLogger(ReviewsActivity.class);

    private CustomListViewAdapter listViewAdapter;

    private AsyncTask<String, Void, List<CommunityReview>> loadReviewsTask;

    public static Intent getNumberIntent(Context context, String number) {
        Intent intent = new Intent(context, ReviewsActivity.class);
        intent.putExtra(PARAM_NUMBER, number);
        intent.setData(IntentHelper.getUriForPhoneNumber(number));
        return intent;
    }

    public static void startForNumber(Context context, String number) {
        context.startActivity(clearTop(getNumberIntent(context, number)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reviews);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        listViewAdapter = new CustomListViewAdapter();
        RecyclerView reviewsList = findViewById(R.id.reviews_list);
        reviewsList.setAdapter(listViewAdapter);
        reviewsList.addItemDecoration(new CustomVerticalDivider(this));

        handleIntent(getIntent());
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        handleIntent(intent);
    }

    @Override
    public void onDestroy() {
        cancelReviewsLoadingTask();

        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    protected void handleIntent(Intent intent) {
        String paramNumber = intent.getStringExtra(PARAM_NUMBER);

        findViewById(R.id.reviews_summary).setVisibility(View.GONE);
        setTitle(paramNumber);
        setText(getString(R.string.reviews_loading));

        cancelReviewsLoadingTask();

        @SuppressLint("StaticFieldLeak")
        AsyncTask<String, Void, List<CommunityReview>> asyncTask = loadReviewsTask
                = new AsyncTask<String, Void, List<CommunityReview>>() {
            @Override
            protected List<CommunityReview> doInBackground(String... params) {
                return YacbHolder.getCommunityReviewsLoader()
                        .loadReviews(params[0], App.getSettings().getCountryCodeForReviews());
            }

            @Override
            protected void onPostExecute(List<CommunityReview> reviews) {
                LOG.debug("onPostExecute() {} reviews", reviews == null ? -1 : reviews.size());
                setText("");
                handleReviews(reviews);
            }
        };
        asyncTask.execute(paramNumber);
    }

    private void cancelReviewsLoadingTask() {
        if (loadReviewsTask != null) {
            loadReviewsTask.cancel(true);
            loadReviewsTask = null;
        }
    }

    private void setText(String text) {
        TextView textView = this.findViewById(R.id.text_view);
        textView.setText(text);
        textView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private void handleReviews(List<CommunityReview> reviews) {
        if (reviews == null) {
            setText(getString(R.string.reviews_loading_error));
            return;
        }

        listViewAdapter.setItems(reviews);
        listViewAdapter.notifyDataSetChanged();
        displaySummary(reviews);

        if (reviews.isEmpty()) {
            setText(getString(R.string.lookup_number_not_found));
        }
    }

    private void displaySummary(List<CommunityReview> reviews) {
        int[] ratings = {0, 0, 0};
        int[] resIds = {
                R.id.summary_text_negative,
                R.id.summary_text_neutral,
                R.id.summary_text_positive
        };
        for (CommunityReview review : reviews) {
            switch (review.getRating()) {
                case NEGATIVE:
                    ratings[0]++;
                    break;
                case NEUTRAL:
                    ratings[1]++;
                    break;
                case POSITIVE:
                    ratings[2]++;
                    break;
            }
        }
        View summary = findViewById(R.id.reviews_summary);
        summary.setVisibility(View.VISIBLE);
        for (int i = 0; i < resIds.length; i++) {
            ((TextView) summary.findViewById(resIds[i])).setText(String.valueOf(ratings[i]));
        }
    }

}
