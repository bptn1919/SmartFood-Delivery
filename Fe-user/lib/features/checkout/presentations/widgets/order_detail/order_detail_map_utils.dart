import 'package:google_maps_flutter/google_maps_flutter.dart';

bool isValidOrderCoordinate(double? lat, double? lng) {
  if (lat == null || lng == null) return false;
  if (lat == 0.0 || lng == 0.0) return false;
  return lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
}

bool isValidOrderLatLng(LatLng? point) {
  return point != null &&
      isValidOrderCoordinate(point.latitude, point.longitude);
}

LatLng? orderLatLngFromCoordinates(double? lat, double? lng) {
  if (!isValidOrderCoordinate(lat, lng)) return null;
  return LatLng(lat!, lng!);
}
