package com.newtube.mobile.car;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

/** One Android Auto session, showing the live player on the car display. */
public class NewTubeCarSession extends Session {
    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        return new CarVideoScreen(getCarContext());
    }
}
