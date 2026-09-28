import 'dart:async';

import 'package:firebase_database/firebase_database.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../../models/customer_order.dart';
import 'order_detail_map_utils.dart';
import 'order_detail_styles.dart';

class TrackingMapSection extends StatefulWidget {
  final String orderUid;
  final String deliveryType;
  final OrderStatus status;
  final double? deliveryLatitude;
  final double? deliveryLongitude;
  final String? trackingLink;

  const TrackingMapSection({
    super.key,
    required this.orderUid,
    required this.deliveryType,
    required this.status,
    required this.deliveryLatitude,
    required this.deliveryLongitude,
    this.trackingLink,
  });

  @override
  State<TrackingMapSection> createState() => _TrackingMapSectionState();
}

class _TrackingMapSectionState extends State<TrackingMapSection> {
  final _dbRef = FirebaseDatabase.instance.ref("active_deliveries");
  StreamSubscription<DatabaseEvent>? _trackingSub;
  LatLng? _chefRealtimeLoc;
  double _chefHeading = 0.0;
  GoogleMapController? _mapController;

  @override
  void initState() {
    super.initState();
    _startListeningToChef();
  }

  @override
  void didUpdateWidget(covariant TrackingMapSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.orderUid != widget.orderUid) {
      _trackingSub?.cancel();
      _chefRealtimeLoc = null;
      _chefHeading = 0.0;
      _startListeningToChef();
    }
  }

  @override
  void dispose() {
    _trackingSub?.cancel();
    _mapController?.dispose();
    super.dispose();
  }

  LatLng? _trackingTarget() {
    if (isValidOrderLatLng(_chefRealtimeLoc)) return _chefRealtimeLoc;
    return orderLatLngFromCoordinates(
      widget.deliveryLatitude,
      widget.deliveryLongitude,
    );
  }

  void _startListeningToChef() {
    _trackingSub = _dbRef.child(widget.orderUid).onValue.listen((event) {
      if (event.snapshot.value == null) return;

      final data = Map<String, dynamic>.from(event.snapshot.value as Map);
      final lat = data['lat'];
      final lng = data['lng'];
      if (lat is! num || lng is! num) return;

      final nextLoc = orderLatLngFromCoordinates(
        lat.toDouble(),
        lng.toDouble(),
      );
      if (nextLoc == null || !mounted) return;

      setState(() {
        _chefRealtimeLoc = nextLoc;
        _chefHeading = (data['heading'] as num?)?.toDouble() ?? 0.0;
      });

      _mapController?.animateCamera(CameraUpdate.newLatLng(nextLoc));
    });
  }

  @override
  Widget build(BuildContext context) {
    if (widget.deliveryType == 'SELF_PICKUP') {
      return const SizedBox.shrink();
    }

    if (widget.deliveryType == 'THIRD_PARTY') {
    if (widget.status != OrderStatus.DELIVERING || widget.trackingLink == null) {
      return const _TrackingPendingPlaceholder();
    }
    
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const OrderDetailSectionHeader(title: 'Live Tracking (Ahamove)'),
        Container(
          height: 300,
          margin: const EdgeInsets.only(bottom: 24),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.grey.shade300),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: WebViewWidget(
              controller: WebViewController()
                ..setJavaScriptMode(JavaScriptMode.unrestricted)
                ..loadRequest(Uri.parse(widget.trackingLink!)),
            ),
          ),
        ),
      ],
    );
  }

    if (widget.status != OrderStatus.DELIVERING) {
      return const _TrackingPendingPlaceholder();
    }

    final destination = orderLatLngFromCoordinates(
      widget.deliveryLatitude,
      widget.deliveryLongitude,
    );
    final chefLoc =
        isValidOrderLatLng(_chefRealtimeLoc) ? _chefRealtimeLoc : null;
    final initialCameraPos = _trackingTarget();

    if (initialCameraPos == null) {
      return const SizedBox.shrink();
    }

    final markers = <Marker>{};
    final polylines = <Polyline>{};

    if (destination != null) {
      markers.add(
        Marker(
          markerId: const MarkerId('destination'),
          position: destination,
          infoWindow: const InfoWindow(title: "Your Address"),
          icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueRed),
        ),
      );
    }

    if (chefLoc != null) {
      markers.add(
        Marker(
          markerId: const MarkerId('chef'),
          position: chefLoc,
          rotation: _chefHeading,
          infoWindow: const InfoWindow(title: "Chef is coming!"),
          icon:
              BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueAzure),
        ),
      );

      if (destination != null) {
        polylines.add(Polyline(
          polylineId: const PolylineId('route'),
          points: [chefLoc, destination],
          color: Colors.blueAccent,
          width: 4,
          patterns: [PatternItem.dash(20), PatternItem.gap(10)],
        ));
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const OrderDetailSectionHeader(title: 'Live Tracking'),
        Container(
          height: 250,
          margin: const EdgeInsets.only(bottom: 24),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.grey.shade300),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.05),
                blurRadius: 8,
                offset: const Offset(0, 4),
              )
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: GoogleMap(
              key: ValueKey(
                'tracking-map-${destination?.latitude}-${destination?.longitude}-${chefLoc != null}',
              ),
              initialCameraPosition:
                  CameraPosition(target: initialCameraPos, zoom: 15),
              markers: markers,
              polylines: polylines,
              gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
                Factory<OneSequenceGestureRecognizer>(
                  () => EagerGestureRecognizer(),
                ),
              },
              scrollGesturesEnabled: true,
              zoomGesturesEnabled: true,
              zoomControlsEnabled: true,
              myLocationButtonEnabled: false,
              onMapCreated: (controller) {
                _mapController = controller;
                Future.delayed(const Duration(milliseconds: 250), () {
                  if (!mounted) return;
                  final target = _trackingTarget();
                  if (target == null) return;
                  controller.animateCamera(
                    CameraUpdate.newLatLngZoom(target, 15),
                  );
                });
              },
            ),
          ),
        ),
      ],
    );
  }
}

class _TrackingPendingPlaceholder extends StatelessWidget {
  const _TrackingPendingPlaceholder();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const OrderDetailSectionHeader(title: 'Live Tracking'),
        Container(
          width: double.infinity,
          margin: const EdgeInsets.only(bottom: 24),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 20),
          decoration: BoxDecoration(
            color: kOrderDetailLightPinkBg,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: kOrderDetailPrimaryRed.withValues(alpha: 0.16),
            ),
          ),
          child: const Row(
            children: [
              Icon(
                Icons.delivery_dining_outlined,
                color: kOrderDetailPrimaryRed,
                size: 28,
              ),
              SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Tracking map will be available once the delivery starts',
                  style: TextStyle(
                    color: kOrderDetailTextBrown,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    height: 1.3,
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
