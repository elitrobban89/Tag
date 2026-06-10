package com.minipristaget;

import java.time.LocalDate;

public class SearchFormRequest {
    private String from      = "";
    private String to        = "";
    private String date      = LocalDate.now().toString();
    private String tripType  = "enkel";
    private String returnDate = "";

    public String getFrom()             { return from; }
    public void   setFrom(String v)     { this.from = v; }

    public String getTo()               { return to; }
    public void   setTo(String v)       { this.to = v; }

    public String getDate()             { return date; }
    public void   setDate(String v)     { this.date = v; }

    public String getTripType()         { return tripType; }
    public void   setTripType(String v) { this.tripType = v; }

    public String getReturnDate()           { return returnDate; }
    public void   setReturnDate(String v)   { this.returnDate = v; }
}
