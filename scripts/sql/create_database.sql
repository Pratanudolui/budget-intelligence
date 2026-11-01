-- Run as the PostgreSQL superuser (postgres). Creates a local-only role and database for the app.
-- The password below is a local development default; change it and set DB_PASSWORD accordingly.
CREATE ROLE budget_app WITH LOGIN PASSWORD 'budget_app';
CREATE DATABASE budget_intel OWNER budget_app;
