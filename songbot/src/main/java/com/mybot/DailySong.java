package com.mybot;

public class DailySong {
    public String date;
    public String songName;
    public String author;
    public String audioPath;

    public DailySong(String date, String songName, String author, String audioPath) {
        this.date = date;
        this.songName = songName;
        this.author = author;
        this.audioPath = audioPath;
    }
}