// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.dialogfragment;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.databinding.AlarmShiftCycleDialogBinding;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

/**
 * Dialog to configure the "work X days, rest Y days" shift cycle of an alarm.
 *
 * <p>The user enters the number of consecutive work days, the number of consecutive rest days
 * and the start date of the first work block. On OK the values are sent back to the caller
 * through {@link #REQUEST_KEY} using the fragment result API.</p>
 */
public class ShiftCycleDialogFragment extends DialogFragment {

    /**
     * The tag that identifies instances of ShiftCycleDialogFragment in the fragment manager.
     */
    private static final String TAG = "shift_cycle_dialog";

    private static final String ARG_WORK_DAYS = "arg_work_days";
    private static final String ARG_REST_DAYS = "arg_rest_days";
    private static final String ARG_START_DATE = "arg_start_date";

    public static final String REQUEST_KEY = "shift_cycle_request_key";
    public static final String WORK_DAYS_VALUE = "shift_cycle_work_days";
    public static final String REST_DAYS_VALUE = "shift_cycle_rest_days";
    public static final String START_DATE_VALUE = "shift_cycle_start_date";

    private AlarmShiftCycleDialogBinding mBinding;
    private Button mOkButton;
    private Typeface mTypeface;
    private long mStartDateUtc;

    private final TextWatcher mTextWatcher = new TextChangeListener();

    public static ShiftCycleDialogFragment newInstance(int workDays, int restDays, long startDateUtc) {
        final Bundle args = new Bundle();
        args.putInt(ARG_WORK_DAYS, workDays);
        args.putInt(ARG_REST_DAYS, restDays);
        args.putLong(ARG_START_DATE, startDateUtc);

        final ShiftCycleDialogFragment fragment = new ShiftCycleDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public static void show(FragmentManager manager, ShiftCycleDialogFragment fragment) {
        Utils.showDialogFragment(manager, fragment, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final SharedPreferences prefs = getDefaultSharedPreferences(requireContext());
        mTypeface = ThemeUtils.loadFont(SettingsDAO.getGeneralFont(prefs));

        final Bundle args = requireArguments();
        int workDays = args.getInt(ARG_WORK_DAYS, 1);
        int restDays = args.getInt(ARG_REST_DAYS, 1);
        mStartDateUtc = args.getLong(ARG_START_DATE, utcMidnightToday());

        mBinding = AlarmShiftCycleDialogBinding.inflate(getLayoutInflater());

        mBinding.editWorkDays.setTypeface(mTypeface);
        mBinding.editWorkDays.setText(String.valueOf(workDays));
        mBinding.editWorkDays.selectAll();
        mBinding.editWorkDays.addTextChangedListener(mTextWatcher);
        mBinding.editWorkDays.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                mBinding.editWorkDays.selectAll();
            }
        });

        mBinding.editRestDays.setTypeface(mTypeface);
        mBinding.editRestDays.setText(String.valueOf(restDays));
        mBinding.editRestDays.selectAll();
        mBinding.editRestDays.addTextChangedListener(mTextWatcher);
        mBinding.editRestDays.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                mBinding.editRestDays.selectAll();
            }
        });

        mBinding.startDateTitle.setTypeface(mTypeface);
        mBinding.startDateValue.setTypeface(mTypeface);
        updateStartDateLabel();

        mBinding.startDateTitle.setOnClickListener(v -> showStartDatePicker());

        return CustomDialog.create(
            requireContext(),
            null,
            null,
            getString(R.string.shift_cycle_title),
            null,
            mBinding.getRoot(),
            getString(android.R.string.ok),
            (d, w) -> applyShiftCycle(),
            getString(android.R.string.cancel),
            null,
            null,
            null,
            alertDialog -> {
                mOkButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                updateOkButtonState();
            },
            CustomDialog.SoftInputMode.SHOW_KEYBOARD
        );
    }

    @Override
    public void onDestroyView() {
        mBinding.editWorkDays.removeTextChangedListener(mTextWatcher);
        mBinding.editRestDays.removeTextChangedListener(mTextWatcher);
        mBinding = null;
        mOkButton = null;
        mTypeface = null;
        super.onDestroyView();
    }

    private void showStartDatePicker() {
        final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(mStartDateUtc);

        final MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(mStartDateUtc)
            .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            mStartDateUtc = selection;
            updateStartDateLabel();
        });

        picker.show(getParentFragmentManager(), "shift_cycle_start_date");
    }

    private void updateStartDateLabel() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        mBinding.startDateValue.setText(getString(R.string.shift_start_date_value, format.format(new Date(mStartDateUtc))));
    }

    private void applyShiftCycle() {
        final int workDays = parseOrZero(mBinding.editWorkDays.getText());
        final int restDays = parseOrZero(mBinding.editRestDays.getText());

        if (workDays <= 0 || restDays <= 0) {
            return;
        }

        final Bundle result = new Bundle();
        result.putInt(WORK_DAYS_VALUE, workDays);
        result.putInt(REST_DAYS_VALUE, restDays);
        result.putLong(START_DATE_VALUE, mStartDateUtc);

        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
    }

    private void updateOkButtonState() {
        if (mOkButton == null) {
            return;
        }

        final int workDays = parseOrZero(mBinding.editWorkDays.getText());
        final int restDays = parseOrZero(mBinding.editRestDays.getText());
        mOkButton.setEnabled(workDays > 0 && restDays > 0);
    }

    private static int parseOrZero(Editable editable) {
        final String text = editable != null ? editable.toString() : "";
        if (text.isEmpty()) {
            return 0;
        }

        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long utcMidnightToday() {
        final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        final Calendar now = Calendar.getInstance();
        utc.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    private class TextChangeListener implements TextWatcher {
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            updateOkButtonState();
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }
}
