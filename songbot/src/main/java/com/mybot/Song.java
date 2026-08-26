package com.mybot;

public class Song {
    private int id;
    private String songName, author, charter;
    private String bpm;
    private String duration, album, imagePath, audioPath;
    private String albumIds;
    private String songNickname, artistNickname;

    // V75 新增: 额外花名
    private String songNickname2, songNickname3, songNickname4, songNickname5, songNickname6;

    private String albumDate;
    private String albumImagePath;

    private String _4k_ez, _4k_nm, _4k_hd, _4k_mx, _4k_sp;
    private String _5k_ez, _5k_nm, _5k_hd, _5k_mx, _5k_sp;
    private String _6k_ez, _6k_nm, _6k_hd, _6k_mx, _6k_sp;
    private String _7k_ez, _7k_nm, _7k_hd, _7k_mx, _7k_sp;
    private String _8k_ez, _8k_nm, _8k_hd, _8k_mx, _8k_sp;

    public Song(int id, String songName, String author, String charter, String bpm, String duration, String album,
                String albumIds, String songNickname, String artistNickname,
                // 构造参数新增
                String songNickname2, String songNickname3, String songNickname4, String songNickname5, String songNickname6,

                String albumDate, String albumImagePath,

                String _4k_ez, String _4k_nm, String _4k_hd, String _4k_mx, String _4k_sp,
                String _5k_ez, String _5k_nm, String _5k_hd, String _5k_mx, String _5k_sp,
                String _6k_ez, String _6k_nm, String _6k_hd, String _6k_mx, String _6k_sp,
                String _7k_ez, String _7k_nm, String _7k_hd, String _7k_mx, String _7k_sp,
                String _8k_ez, String _8k_nm, String _8k_hd, String _8k_mx, String _8k_sp,
                String imagePath, String audioPath) {
        this.id = id; this.songName = songName; this.author = author; this.charter = charter; this.bpm = bpm;
        this.duration = duration; this.album = album;
        this.albumIds = albumIds; this.songNickname = songNickname; this.artistNickname = artistNickname;

        // V75 赋值
        this.songNickname2 = songNickname2; this.songNickname3 = songNickname3;
        this.songNickname4 = songNickname4; this.songNickname5 = songNickname5; this.songNickname6 = songNickname6;

        this.albumDate = albumDate; this.albumImagePath = albumImagePath;

        this._4k_ez = _4k_ez; this._4k_nm = _4k_nm; this._4k_hd = _4k_hd; this._4k_mx = _4k_mx; this._4k_sp = _4k_sp;
        this._5k_ez = _5k_ez; this._5k_nm = _5k_nm; this._5k_hd = _5k_hd; this._5k_mx = _5k_mx; this._5k_sp = _5k_sp;
        this._6k_ez = _6k_ez; this._6k_nm = _6k_nm; this._6k_hd = _6k_hd; this._6k_mx = _6k_mx; this._6k_sp = _6k_sp;
        this._7k_ez = _7k_ez; this._7k_nm = _7k_nm; this._7k_hd = _7k_hd; this._7k_mx = _7k_mx; this._7k_sp = _7k_sp;
        this._8k_ez = _8k_ez; this._8k_nm = _8k_nm; this._8k_hd = _8k_hd; this._8k_mx = _8k_mx; this._8k_sp = _8k_sp;
        this.imagePath = imagePath; this.audioPath = audioPath;
    }

    // Getters
    public int getId() { return id; } public String getSongName() { return songName; } public String getAuthor() { return author; } public String getCharter() { return charter; } public String getBpm() { return bpm; } public String getDuration() { return duration; } public String getAlbum() { return album; }
    public String getAlbumIds() { return albumIds; } public String getSongNickname() { return songNickname; } public String getArtistNickname() { return artistNickname; }

    // V75 Getters
    public String getSongNickname2() { return songNickname2; } public String getSongNickname3() { return songNickname3; }
    public String getSongNickname4() { return songNickname4; }
    public String getSongNickname5() { return songNickname5; }
    public String getSongNickname6() { return songNickname6; }
    public String getAlbumDate() { return albumDate; } public String getAlbumImagePath() { return albumImagePath; }
    public String getImagePath() { return imagePath; } public String getAudioPath() { return audioPath; }

    public String get_4k_ez() { return _4k_ez; } public String get_4k_nm() { return _4k_nm; } public String get_4k_hd() { return _4k_hd; } public String get_4k_mx() { return _4k_mx; } public String get_4k_sp() { return _4k_sp; }
    public String get_5k_ez() { return _5k_ez; } public String get_5k_nm() { return _5k_nm; } public String get_5k_hd() { return _5k_hd; } public String get_5k_mx() { return _5k_mx; } public String get_5k_sp() { return _5k_sp; }
    public String get_6k_ez() { return _6k_ez; } public String get_6k_nm() { return _6k_nm; } public String get_6k_hd() { return _6k_hd; } public String get_6k_mx() { return _6k_mx; } public String get_6k_sp() { return _6k_sp; }
    public String get_7k_ez() { return _7k_ez; } public String get_7k_nm() { return _7k_nm; } public String get_7k_hd() { return _7k_hd; } public String get_7k_mx() { return _7k_mx; } public String get_7k_sp() { return _7k_sp; }
    public String get_8k_ez() { return _8k_ez; } public String get_8k_nm() { return _8k_nm; } public String get_8k_hd() { return _8k_hd; } public String get_8k_mx() { return _8k_mx; } public String get_8k_sp() { return _8k_sp; }
}