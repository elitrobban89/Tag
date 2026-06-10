package com.minipristaget;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class TrainDeparture {
    private String  trainId;
    private String  departureTime;
    private String  estimatedTime;
    private String  destination;
    private String  operator;
    private boolean canceled;

    // Enriched fields
    private String price;        // 2 klass
    private String priceLugn;    // 2 klass Lugn
    private String price1klass;  // 1 klass
    private String trainModel;
    private String trainColor;
    private String trainImage;
    private int    travelMinutes;
    private int    transfers;
    private double co2SavedKg;

    @JsonIgnore
    private String destinationSignature;

    public String getTrainId()        { return trainId; }
    public void setTrainId(String v)  { this.trainId = v; }

    public String getDepartureTime()       { return departureTime; }
    public void setDepartureTime(String v) { this.departureTime = v; }

    public String getEstimatedTime()       { return estimatedTime; }
    public void setEstimatedTime(String v) { this.estimatedTime = v; }

    public String getDestination()       { return destination; }
    public void setDestination(String v) { this.destination = v; }

    public String getOperator()       { return operator; }
    public void setOperator(String v) { this.operator = v; }

    public boolean isCanceled()        { return canceled; }
    public void setCanceled(boolean v) { this.canceled = v; }

    public String getPrice()       { return price; }
    public void setPrice(String v) { this.price = v; }

    public String getPriceLugn()       { return priceLugn; }
    public void setPriceLugn(String v) { this.priceLugn = v; }

    public String getPrice1klass()       { return price1klass; }
    public void setPrice1klass(String v) { this.price1klass = v; }

    public String getTrainModel()       { return trainModel; }
    public void setTrainModel(String v) { this.trainModel = v; }

    public String getTrainColor()       { return trainColor; }
    public void setTrainColor(String v) { this.trainColor = v; }

    public String getTrainImage()       { return trainImage; }
    public void setTrainImage(String v) { this.trainImage = v; }

    public int  getTravelMinutes()     { return travelMinutes; }
    public void setTravelMinutes(int v){ this.travelMinutes = v; }

    public int  getTransfers()     { return transfers; }
    public void setTransfers(int v){ this.transfers = v; }

    public double getCo2SavedKg()       { return co2SavedKg; }
    public void   setCo2SavedKg(double v){ this.co2SavedKg = v; }

    public String getDestinationSignature()       { return destinationSignature; }
    public void setDestinationSignature(String v) { this.destinationSignature = v; }

    public boolean isDelayed() {
        return estimatedTime != null && !estimatedTime.isBlank()
            && !estimatedTime.equals(departureTime);
    }
}
