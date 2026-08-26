package com.mybot;

public class Album {
    private int albumId;
    private String albumName;
    private String albumCategory;

    public Album(int albumId, String albumName, String albumCategory) {
        this.albumId = albumId;
        this.albumName = albumName;
        this.albumCategory = albumCategory;
    }

    public int getAlbumId() { return albumId; }
    public String getAlbumName() { return albumName; }
    public String getAlbumCategory() { return albumCategory; }
}