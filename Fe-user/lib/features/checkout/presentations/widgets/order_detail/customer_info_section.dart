import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../models/customer_order.dart';
import '../../../models/order_detail.dart';
import 'order_detail_map_utils.dart';
import 'order_detail_styles.dart';

class CustomerInfoSection extends StatelessWidget {
  final OrderDetail order;
  final Color accentColor;
  final VoidCallback? onEditDeliveryTime;

  const CustomerInfoSection({
    super.key,
    required this.order,
    required this.accentColor,
    this.onEditDeliveryTime,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const OrderDetailSectionHeader(title: 'Customer Information'),
        OrderDetailInfoTile(
          icon: Icons.person_outline,
          title: order.fullName,
          subtitle: order.phoneNumber,
        ),
        const SizedBox(height: 12),
        if (order.deliveryType == 'SELF_PICKUP')
          SelfPickupInfoSection(
            order: order,
            accentColor: accentColor,
            onEditDeliveryTime: onEditDeliveryTime,
          )
        else
          _DeliveryAddressInfo(
            order: order,
            accentColor: accentColor,
            onEditDeliveryTime: onEditDeliveryTime,
          ),
      ],
    );
  }
}

class _DeliveryAddressInfo extends StatelessWidget {
  final OrderDetail order;
  final Color accentColor;
  final VoidCallback? onEditDeliveryTime;

  const _DeliveryAddressInfo({
    required this.order,
    required this.accentColor,
    this.onEditDeliveryTime,
  });

  @override
  Widget build(BuildContext context) {
    final canEdit = order.status == OrderStatus.DRAFT;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: kOrderDetailDivider),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.location_on_outlined, color: Colors.black54),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  order.deliveryAddress ?? 'Address not updated',
                  style: const TextStyle(
                    color: kOrderDetailTextBrown,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '${order.deliveryDate} • ${order.deliveryTime}',
                  style: TextStyle(color: Colors.grey.shade700, fontSize: 13),
                ),
              ],
            ),
          ),
          if (canEdit && onEditDeliveryTime != null)
            TextButton.icon(
              onPressed: onEditDeliveryTime,
              icon: Icon(Icons.schedule_outlined, size: 16, color: accentColor),
              label: Text(
                'Edit Time',
                style: TextStyle(
                  color: accentColor,
                  fontWeight: FontWeight.bold,
                  fontSize: 12,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class SelfPickupInfoSection extends StatefulWidget {
  final OrderDetail order;
  final Color accentColor;
  final VoidCallback? onEditDeliveryTime;

  const SelfPickupInfoSection({
    super.key,
    required this.order,
    required this.accentColor,
    this.onEditDeliveryTime,
  });

  @override
  State<SelfPickupInfoSection> createState() => _SelfPickupInfoSectionState();
}

class _SelfPickupInfoSectionState extends State<SelfPickupInfoSection> {
  @override
  Widget build(BuildContext context) {
    final order = widget.order;
    final accentColor = widget.accentColor;
    final canEditTime = order.status == OrderStatus.DRAFT;
    final rawLat = order.chefLatitude;
    final rawLng = order.chefLongitude;
    final isValidCoordinate = isValidOrderCoordinate(rawLat, rawLng);
    final chefLoc = orderLatLngFromCoordinates(
      rawLat,
      rawLng,
    );
    final mapKey = chefLoc == null
        ? null
        : ValueKey(
            'self-pickup-map-${chefLoc.latitude}-${chefLoc.longitude}',
          );

    debugPrint(
      '📍 [SELF-PICKUP MAP] raw chefLatitude=$rawLat, chefLongitude=$rawLng',
    );
    debugPrint(
      '📍 [SELF-PICKUP MAP] coordinate validation result=$isValidCoordinate',
    );

    if (chefLoc == null || mapKey == null) {
      debugPrint(
        '📍 [SELF-PICKUP MAP] invalid coordinates; GoogleMap will be hidden',
      );
    } else {
      debugPrint(
        '📍 [SELF-PICKUP MAP] GoogleMap LatLng=$chefLoc, ValueKey=${mapKey.value}',
      );
    }

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: kOrderDetailLightPinkBg,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: accentColor.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.white,
                  shape: BoxShape.circle,
                  border: Border.all(color: accentColor.withValues(alpha: 0.2)),
                ),
                child: Icon(Icons.storefront_outlined,
                    color: accentColor, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      "Pick-up at Chef's Kitchen",
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: accentColor,
                        fontSize: 15,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      order.chefName.isNotEmpty
                          ? order.chefName
                          : 'Unknown Chef',
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        color: kOrderDetailTextBrown,
                        fontSize: 14,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      order.chefAddress ?? 'No address provided',
                      style: TextStyle(
                        color: Colors.grey.shade700,
                        fontSize: 13,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: kOrderDetailDivider),
            ),
            child: Row(
              children: [
                Icon(Icons.schedule_outlined, color: accentColor, size: 20),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    '${order.deliveryDate} • ${order.deliveryTime}',
                    style: const TextStyle(
                      color: kOrderDetailTextBrown,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                if (canEditTime && widget.onEditDeliveryTime != null)
                  TextButton(
                    onPressed: widget.onEditDeliveryTime,
                    child: Text(
                      'Edit Time',
                      style: TextStyle(
                        color: accentColor,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          if (chefLoc != null && mapKey != null) ...[
            const SizedBox(height: 16),
            const Text(
              'Kitchen Location',
              style: TextStyle(
                color: kOrderDetailTextBrown,
                fontSize: 14,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 160,
              width: double.infinity,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: GoogleMap(
                  key: mapKey,
                  initialCameraPosition: CameraPosition(
                    target: chefLoc,
                    zoom: 16,
                  ),
                  onMapCreated: (GoogleMapController controller) {
                    debugPrint(
                      '📍 [SELF-PICKUP MAP] onMapCreated fired for LatLng=$chefLoc',
                    );
                    Future.delayed(const Duration(milliseconds: 200), () {
                      debugPrint(
                        '📍 [SELF-PICKUP MAP] delayed camera animation callback executing for LatLng=$chefLoc',
                      );
                      if (!mounted) {
                        debugPrint(
                          '📍 [SELF-PICKUP MAP] widget unmounted; skipping camera animation',
                        );
                        return;
                      }
                      controller.animateCamera(
                        CameraUpdate.newLatLngZoom(chefLoc, 16.0),
                      );
                      debugPrint(
                        '📍 [SELF-PICKUP MAP] camera animation requested for LatLng=$chefLoc',
                      );
                    });
                  },
                  markers: {
                    Marker(
                      markerId: const MarkerId('chef_location'),
                      position: chefLoc,
                      infoWindow: InfoWindow(title: order.chefName),
                      icon: BitmapDescriptor.defaultMarkerWithHue(
                        BitmapDescriptor.hueRed,
                      ),
                    )
                  },
                  zoomGesturesEnabled: false,
                  scrollGesturesEnabled: false,
                  tiltGesturesEnabled: false,
                  rotateGesturesEnabled: false,
                  zoomControlsEnabled: false,
                  myLocationButtonEnabled: false,
                  mapToolbarEnabled: true,
                ),
              ),
            ),
          ]
        ],
      ),
    );
  }
}
