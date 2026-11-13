package com.codehub.app.codesu.data;

import androidx.room.RoomDatabase;

import com.codehub.app.codesu.model.PKGName;

@androidx.room.Database(entities = {PKGName.class}, version = 1, exportSchema = false)
public abstract class Database extends RoomDatabase {
    public abstract excludedPkgDao excludedPkgDao();
}
