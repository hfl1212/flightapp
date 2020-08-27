CREATE TABLE Users (
    username varchar(20) primary key,
    passwordHash varbinary(20),
    salted varbinary(20),
    balance int
);

CREATE TABLE Reservations (
    reserveID int primary key,
    username varchar(20) references Users,
    itID int,
    ifdirect int, -- yes-1, no-0
    fid1 int references Flights,
    fid2 int references Flights,
    cost int,
    ifpaid int, -- yes-1, no-0
    ifcancelled int,  -- yes-1, no-0
    dayOfMonth int
);

CREATE TABLE Remainseat (
    fid int REFERENCES Flights,
    seats int
);

