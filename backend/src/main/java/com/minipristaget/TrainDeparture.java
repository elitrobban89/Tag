package com.minipristaget;

public class TrainDeparture {
    private String trainId;
    private String departureTime;
    private String estimatedTime;
    private String destination;
    private String operator;
    private boolean canceled;

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

    public boolean isCanceled()       { return canceled; }
    public void setCanceled(boolean v) { this.canceled = v; }

    public boolean isDelayed() {
        return estimatedTime != null && !estimatedTime.isBlank()
            && !estimatedTime.equals(departureTime);
    }
}
