package com.minipristaget;

public class TrainStation {
    private String signature;
    private String name;
    private double lat;
    private double lon;

    public TrainStation(String signature, String name, double lat, double lon) {
        this.signature = signature;
        this.name      = name;
        this.lat       = lat;
        this.lon       = lon;
    }

    public String getSignature() { return signature; }
    public String getName()      { return name; }
    public double getLat()       { return lat; }
    public double getLon()       { return lon; }
}
