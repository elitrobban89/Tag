package com.minipristaget;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** @author Robert Andersson Kopler */
public class TrainDeparture {
    private String  trainId;
    private String  departureTime;
    private String  estimatedTime;
    private String  destination;
    private String  operator;
    private boolean canceled;

    // Enriched fields
    private String price;          // 2 klass MiniPris
    private String priceLugn;      // 2 klass Lugn MiniPris
    private String price1klass;    // 1 klass MiniPris
    private int     priceOriginal;  // ordinarie pris (visas överstruket)
    private int     seatsLeft;      // platser kvar
    private boolean hasSeatMap;     // om tågtypen stöder platsval
    private String  seatLayout;     // "x2000", "x74", "snalltaget", "mtr", "none"
    private String  trainModel;
    private String trainColor;
    private String trainImage;
    private int    travelMinutes;
    private int    transfers;
    private double co2SavedKg;

    @JsonIgnore
    private String destinationSignature;

    /** Trafikverkets produktnamn ("SJ Snabbtåg", "SJ Regional"…) — inte fordonstyp. */
    private String productInformation;

    // ── Resa med byte (transfers == 1) ──
    private String transferStation;    // "Alvesta"
    private String transferArrival;    // HH:mm framme vid bytet
    private String transferDeparture;  // HH:mm avgång från bytet
    private String secondTrainId;
    /** Beräknad ankomst vid målet (HH:mm) — bara satt när tåget ligger efter tidtabellen. */
    private String estimatedArrival;
    /**
     * Tågets slutstation när den INTE är resenärens mål (Göteborg → Katrineholm med ett tåg
     * mot Stockholm C). {@code destination} är då målet man sökt — där man kliver av.
     */
    private String finalDestination;

    public String getFinalDestination()         { return finalDestination; }
    public void   setFinalDestination(String v) { this.finalDestination = v; }

    /** Trafikverkets trafiktyp: "Tåg", "Pendeltåg" eller "Buss" (ersättningsbuss). */
    private String typeOfTraffic;
    /** Bolaget som visas på märket: "SJ", "VR", "Mälartåg", "SL" … */
    private String operatorName;
    /** Bytesstationerna i ordning (en eller två) — för chattens resekarta. */
    private java.util.List<String> transferStops;
    /** "Linköping C 08:05 → 08:20" per byte, i ordning. */
    private java.util.List<String> transferDetails;

    public String getTypeOfTraffic()         { return typeOfTraffic; }
    public void   setTypeOfTraffic(String v) { this.typeOfTraffic = v; }
    public String getOperatorName()         { return operatorName; }
    public void   setOperatorName(String v) { this.operatorName = v; }
    public java.util.List<String> getTransferStops()         { return transferStops; }
    public void   setTransferStops(java.util.List<String> v) { this.transferStops = v; }
    public java.util.List<String> getTransferDetails()         { return transferDetails; }
    public void   setTransferDetails(java.util.List<String> v) { this.transferDetails = v; }

    public String getTransferStation()         { return transferStation; }
    public void   setTransferStation(String v) { this.transferStation = v; }
    public String getTransferArrival()         { return transferArrival; }
    public void   setTransferArrival(String v) { this.transferArrival = v; }
    public String getTransferDeparture()         { return transferDeparture; }
    public void   setTransferDeparture(String v) { this.transferDeparture = v; }
    public String getSecondTrainId()         { return secondTrainId; }
    public void   setSecondTrainId(String v) { this.secondTrainId = v; }
    public String getEstimatedArrival()         { return estimatedArrival; }
    public void   setEstimatedArrival(String v) { this.estimatedArrival = v; }

    public String getProductInformation()         { return productInformation; }
    public void   setProductInformation(String v) { this.productInformation = v; }

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

    public int  getPriceOriginal()     { return priceOriginal; }
    public void setPriceOriginal(int v){ this.priceOriginal = v; }

    public int  getSeatsLeft()     { return seatsLeft; }
    public void setSeatsLeft(int v){ this.seatsLeft = v; }

    public boolean isHasSeatMap()        { return hasSeatMap; }
    public void    setHasSeatMap(boolean v){ this.hasSeatMap = v; }

    public String getSeatLayout()       { return seatLayout; }
    public void   setSeatLayout(String v){ this.seatLayout = v; }

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
