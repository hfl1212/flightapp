We created three additional tables to help us with the development.<br>

"Users" table store the username, password, and 
balance of created users. We chose to store the salts and hashes of users' passwords instead of the password in texts.<br>

Also, we have a "Reservations" table that has username as a foreign key referencing "Users", and fid1 and fid2 which represents
the flights in an itinerary (fid2 is null if the itierary is direct). We also has the column 'dayOfMonth' to keep track of the date of the reservation, which can help us
decide whether the user books two flights in the same day. The "Reservations" table contains the most information, because
"book", "pay", "reservation", and "cancel" they all need to use information form it. <br>

Because there might be cases when multiple users booking the same flight,we decided to create a "Remainseat" table to keep track of the number of 
capacity of a flight, and we modify the seats number as users booked or canceled flights. <br>

In the program, we designed an Itinerary comparable class that stores up to 2 Flight objects. We found it necessary because
it will become handy when we implement the "search" method. We use a nested hash map that its first key is the username and its
value is the second hash map, in which the key is the itinerary id and its value is the itinerary object. This captures a lot
of information in a concise way. <br>
![imagename](flightApp%20UML.jpeg)
